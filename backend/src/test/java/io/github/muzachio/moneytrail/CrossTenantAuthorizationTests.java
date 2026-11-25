package io.github.muzachio.moneytrail;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.jayway.jsonpath.JsonPath;
import io.github.muzachio.moneytrail.OpenApiIdInputs.IdInput;
import io.github.muzachio.moneytrail.identity.UserSignedIn;
import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Nobody reaches another person's money (docs/architecture.md, "Security"). For every id that a
 * request can carry in {@code api/openapi.yaml}, in its path, its query or its body, someone who has
 * no right to the resource behind it gets exactly the answer an id that does not exist gets, and
 * nothing is written. "Someone" is another user, someone outside a group, a former member, and the
 * owner using an id of the wrong ledger: a group's account in a personal request, a personal account
 * as a group.
 *
 * <p>{@link #everyIdARequestCanCarryHasACase} reads the spec, so an endpoint that takes an id fails
 * the build until it has a case here.
 */
@AutoConfigureMockMvc
class CrossTenantAuthorizationTests extends PostgresIntegrationTest {

    private static final Path SPEC = Path.of("../api/openapi.yaml");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");

    /** What an id names, which decides who tries it and with what. */
    enum Kind {
        ACCOUNT,
        CATEGORY,
        TRANSACTION,
        GROUP
    }

    /** One request carrying {@code id} where the case says, sent by {@code by}, whose own data fills the rest. */
    @FunctionalInterface
    interface Request {
        MvcTestResult send(Person by, UUID id);
    }

    /**
     * {@code mixedParents} is for a path id under another one, such as an expense under its group
     * (#88). {@link #check} has it make a child in one of the owner's groups and send it under the
     * owner's other group, which must answer like an unknown id too. The suite requires it for
     * every such id in the spec.
     */
    record Case(Kind kind, Request request, @Nullable MixedParents mixedParents) {

        Case(Kind kind, Request request) {
            this(kind, request, null);
        }
    }

    /** How to make a child of a group, and to send a child id under a group's path. */
    record MixedParents(ChildFactory child, ParentRequest request) {}

    @FunctionalInterface
    interface ChildFactory {
        /** Makes a child of {@code groupId}, such as an expense, through the API, and returns its id. */
        UUID create(Person owner, UUID groupId);
    }

    @FunctionalInterface
    interface ParentRequest {
        MvcTestResult send(Person by, UUID groupId, UUID childId);
    }

    record Person(UUID id, UUID account, UUID otherAccount, UUID category, UUID transaction) {}

    /**
     * Ana owns everything. Bea is another user, a stranger to Ana's group. Rita was a member of the
     * group and left. Ana's account in the group's ledger and an entry of that ledger are ids of the
     * wrong ledger for her personal requests. Ana is in a second group too, whose children must not
     * be reached under the first group's path.
     */
    record Fixture(
            Person ana, Person bea, Person rita, UUID group, UUID groupAccount, UUID groupEntry, UUID otherGroup) {}

    record Attempt(String who, Person by, UUID id) {}

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    private final Map<String, Case> cases = Map.ofEntries(
            entry(
                    "updateAccount path.id",
                    new Case(Kind.ACCOUNT, (by, id) -> patch("/v1/accounts/" + id, by.id(), """
                    {"name": "Taken"}"""))),
            entry(
                    "updateCategory path.id",
                    new Case(Kind.CATEGORY, (by, id) -> patch("/v1/categories/" + id, by.id(), """
                    {"name": "Taken"}"""))),
            entry(
                    "listTransactions query.accountId",
                    new Case(Kind.ACCOUNT, (by, id) -> get("/v1/transactions?accountId=" + id, by.id()))),
            entry(
                    "listTransactions query.categoryId",
                    new Case(Kind.CATEGORY, (by, id) -> get("/v1/transactions?categoryId=" + id, by.id()))),
            entry(
                    "createTransaction body.accountId",
                    new Case(Kind.ACCOUNT, (by, id) -> post("/v1/transactions", by.id(), expense(by.category(), id)))),
            entry(
                    "createTransaction body.categoryId",
                    new Case(Kind.CATEGORY, (by, id) -> post("/v1/transactions", by.id(), expense(id, by.account())))),
            entry(
                    "createTransaction body.fromAccountId",
                    new Case(
                            Kind.ACCOUNT,
                            (by, id) -> post("/v1/transactions", by.id(), transfer(id, by.otherAccount())))),
            entry(
                    "createTransaction body.toAccountId",
                    new Case(
                            Kind.ACCOUNT,
                            (by, id) -> post("/v1/transactions", by.id(), transfer(by.otherAccount(), id)))),
            entry(
                    "getTransaction path.id",
                    new Case(Kind.TRANSACTION, (by, id) -> get("/v1/transactions/" + id, by.id()))),
            entry(
                    "reverseTransaction path.id",
                    new Case(Kind.TRANSACTION, (by, id) -> post("/v1/transactions/" + id + "/reverse", by.id(), "{}"))),
            entry(
                    "reverseTransaction body.replacement.accountId",
                    new Case(Kind.ACCOUNT, (by, id) -> reverse(by, expense(by.category(), id)))),
            entry(
                    "reverseTransaction body.replacement.categoryId",
                    new Case(Kind.CATEGORY, (by, id) -> reverse(by, expense(id, by.account())))),
            entry(
                    "reverseTransaction body.replacement.fromAccountId",
                    new Case(Kind.ACCOUNT, (by, id) -> reverse(by, transfer(id, by.otherAccount())))),
            entry(
                    "reverseTransaction body.replacement.toAccountId",
                    new Case(Kind.ACCOUNT, (by, id) -> reverse(by, transfer(by.otherAccount(), id)))),
            entry("getGroup path.id", new Case(Kind.GROUP, (by, id) -> get("/v1/groups/" + id, by.id()))),
            entry("updateGroup path.id", new Case(Kind.GROUP, (by, id) -> patch("/v1/groups/" + id, by.id(), """
                    {"name": "Taken", "archived": true}"""))),
            entry(
                    "leaveGroup path.id",
                    new Case(Kind.GROUP, (by, id) -> post("/v1/groups/" + id + "/leave", by.id(), null))));

    @Test
    void everyIdARequestCanCarryHasACase() throws IOException {
        Set<IdInput> inputs = OpenApiIdInputs.of(SPEC);
        Set<String> inSpec = inputs.stream().map(IdInput::key).collect(Collectors.toSet());

        assertThat(inSpec)
                .as("ids in api/openapi.yaml without a cross-tenant case")
                .isSubsetOf(cases.keySet());
        assertThat(cases.keySet())
                .as("cases for ids no longer in api/openapi.yaml")
                .isSubsetOf(inSpec);
        assertThat(inputs)
                .filteredOn(IdInput::nestedPath)
                .as("ids under another id's path need a case that sends them under another parent")
                .allSatisfy(input -> assertThat(cases.get(input.key()))
                        .isNotNull()
                        .extracting(Case::mixedParents)
                        .isNotNull());
    }

    @TestFactory
    Stream<DynamicTest> nobodyReachesWhatIsNotTheirs() {
        return cases.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> check(entry.getValue())));
    }

    /**
     * No path has a child id until #88, so the mixed-parents check is tried here on a stand-in for a
     * nested endpoint that ignores its parent: it finds another group's child under any path, which
     * the check must report.
     */
    @Test
    void theMixedParentsCheckReportsAChildFoundUnderAnotherGroup() {
        Fixture fixture = fixture();
        MixedParents ignoresItsParent = new MixedParents(
                (owner, groupId) -> groupId, (by, groupId, childId) -> get("/v1/groups/" + childId, by.id()));
        SoftAssertions softly = new SoftAssertions();

        checkMixedParents(ignoresItsParent, fixture, softly);

        assertThat(softly.errorsCollected())
                .singleElement()
                .extracting(Throwable::getMessage)
                .asString()
                .contains("another group's child: answered like an unknown id");
    }

    /**
     * Each attempt must get what the same person gets for an id that does not exist, a "not found"
     * code, and leave the database as it was. Last, the owner sending their own id must not get that
     * answer, so the case does reach the resource.
     */
    private void check(Case testCase) {
        Fixture fixture = fixture();
        SoftAssertions softly = new SoftAssertions();
        MixedParents mixedParents = testCase.mixedParents();
        if (mixedParents != null) {
            checkMixedParents(mixedParents, fixture, softly);
        }
        for (Attempt attempt : attempts(testCase.kind(), fixture)) {
            Answer unknown = Answer.of(testCase.request().send(attempt.by(), UUID.randomUUID()));
            softly.assertThat(unknown.code())
                    .as("%s: an unknown id answers a not-found code", attempt.who())
                    .endsWith("_not_found");

            String before = snapshot();
            Answer answer = Answer.of(testCase.request().send(attempt.by(), attempt.id()));
            softly.assertThat(answer)
                    .as("%s: answered like an unknown id", attempt.who())
                    .isEqualTo(unknown);
            softly.assertThat(snapshot())
                    .as("%s: nothing written", attempt.who())
                    .isEqualTo(before);
        }
        Answer unknown = Answer.of(testCase.request().send(fixture.ana(), UUID.randomUUID()));
        Answer own = Answer.of(testCase.request().send(fixture.ana(), own(testCase.kind(), fixture)));
        softly.assertThat(own).as("the owner's own id is found").isNotEqualTo(unknown);
        softly.assertAll();
    }

    /**
     * A child of Ana's other group, sent under her first group's path, gets what an unknown child
     * gets there and writes nothing; under its own group it is found.
     */
    private void checkMixedParents(MixedParents mixedParents, Fixture f, SoftAssertions softly) {
        UUID child = mixedParents.child().create(f.ana(), f.otherGroup());
        Answer unknown = Answer.of(mixedParents.request().send(f.ana(), f.group(), UUID.randomUUID()));
        softly.assertThat(unknown.code())
                .as("another group's child: an unknown child answers a not-found code")
                .endsWith("_not_found");

        String before = snapshot();
        Answer answer = Answer.of(mixedParents.request().send(f.ana(), f.group(), child));
        softly.assertThat(answer)
                .as("another group's child: answered like an unknown id")
                .isEqualTo(unknown);
        softly.assertThat(snapshot())
                .as("another group's child: nothing written")
                .isEqualTo(before);

        Answer underItsOwnGroup = Answer.of(mixedParents.request().send(f.ana(), f.otherGroup(), child));
        softly.assertThat(underItsOwnGroup)
                .as("the child under its own group is found")
                .isNotEqualTo(unknown);
    }

    private static List<Attempt> attempts(Kind kind, Fixture f) {
        return switch (kind) {
            case ACCOUNT ->
                List.of(
                        new Attempt("another user's account", f.bea(), f.ana().account()),
                        new Attempt("a member using the group's account", f.ana(), f.groupAccount()));
            case CATEGORY ->
                List.of(
                        new Attempt("another user's category", f.bea(), f.ana().category()),
                        new Attempt("a member using the group's account", f.ana(), f.groupAccount()));
            case TRANSACTION ->
                List.of(
                        new Attempt(
                                "another user's transaction", f.bea(), f.ana().transaction()),
                        new Attempt("a member using an entry of the group's ledger", f.ana(), f.groupEntry()));
            case GROUP ->
                List.of(
                        new Attempt("someone outside the group", f.bea(), f.group()),
                        new Attempt("a former member", f.rita(), f.group()),
                        new Attempt(
                                "the owner using a personal account as a group",
                                f.ana(),
                                f.ana().account()));
        };
    }

    private static UUID own(Kind kind, Fixture f) {
        return switch (kind) {
            case ACCOUNT -> f.ana().account();
            case CATEGORY -> f.ana().category();
            case TRANSACTION -> f.ana().transaction();
            case GROUP -> f.group();
        };
    }

    /** The status and the problem's {@code code}, which is all a client can tell apart. */
    record Answer(int status, String code) {

        static Answer of(MvcTestResult result) {
            String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
            String code = body.contains("\"code\"") ? JsonPath.read(body, "$.code") : "";
            return new Answer(result.getResponse().getStatus(), code);
        }
    }

    /**
     * Everything the requests here could write, in one string: if an attempt changes a row, the
     * string changes. Tests run one at a time, so nothing else writes meanwhile.
     */
    private String snapshot() {
        return jdbc.sql("""
                        SELECT concat_ws(' | ',
                            (SELECT string_agg(id::text || ':' || name || ':' || coalesce(archived_at::text, ''), ','
                                               ORDER BY id) FROM accounts),
                            (SELECT string_agg(account_id::text || ':' || balance_minor, ',' ORDER BY account_id)
                             FROM account_balances),
                            (SELECT count(*) FROM journal_entries),
                            (SELECT count(*) FROM postings),
                            (SELECT string_agg(id::text || ':' || name || ':' || coalesce(archived_at::text, ''), ','
                                               ORDER BY id) FROM groups),
                            (SELECT string_agg(group_id::text || ':' || user_id || ':' || coalesce(left_at::text, ''), ','
                                               ORDER BY group_id, user_id) FROM group_members),
                            (SELECT string_agg(category_id::text || ':' || icon, ',' ORDER BY category_id)
                             FROM category_icons),
                            (SELECT string_agg(category_id::text || ':' || key, ',' ORDER BY category_id)
                             FROM category_keys),
                            (SELECT count(*) FROM idempotency_keys))""").query(String.class).single();
    }

    private Fixture fixture() {
        Person ana = person("Ana");
        Person bea = person("Bea");
        Person rita = person("Rita");
        UUID joao = signedInUser("João");

        UUID group = idOf(post("/v1/groups", ana.id(), """
                {"name": "Flat"}"""));
        UUID groupLedger =
                ledger.findLedger(LedgerOwner.group(group)).orElseThrow().id();
        UUID groupAccount = memberAccount(group, ana.id());
        addMember(group, groupLedger, joao);
        addMember(group, groupLedger, rita.id());
        assertThat(post("/v1/groups/" + group + "/leave", rita.id(), null)
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);
        // Joao paid 10.00 for Ana: an entry of the group's ledger, as an expense will post (#88).
        UUID groupEntry = ledger.post(new NewJournalEntry(
                        groupLedger,
                        today(),
                        "Dinner",
                        joao,
                        List.of(
                                new Posting(memberAccount(group, joao), new Money(1000, EUR)),
                                new Posting(groupAccount, new Money(-1000, EUR)))))
                .id();
        UUID otherGroup = idOf(post("/v1/groups", ana.id(), """
                {"name": "Trip"}"""));
        return new Fixture(ana, bea, rita, group, groupAccount, groupEntry, otherGroup);
    }

    /** A user with a personal ledger, two accounts, an expense category and one expense. */
    private Person person(String name) {
        UUID id = signedInUser(name);
        UUID account = idOf(post("/v1/accounts", id, """
                {"name": "Checking", "type": "CHECKING", "currency": "EUR",
                 "openingBalance": {"amountMinor": 100000, "currency": "EUR"}}"""));
        UUID otherAccount = idOf(post("/v1/accounts", id, """
                {"name": "Savings", "type": "SAVINGS", "currency": "EUR"}"""));
        String categories =
                new String(get("/v1/categories", id).getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        List<String> expenseCategories = JsonPath.read(categories, "$.items[?(@.kind == 'EXPENSE')].id");
        UUID category = UUID.fromString(expenseCategories.getFirst());
        UUID transaction = idOf(post("/v1/transactions", id, expense(category, account)));
        return new Person(id, account, otherAccount, category, transaction);
    }

    private UUID signedInUser(String name) {
        UUID userId = jdbc.sql("INSERT INTO users (email, name) VALUES (:email, :name) RETURNING id")
                .param("email", "cross-tenant-" + UUID.randomUUID() + "@example.com")
                .param("name", name)
                .query(UUID.class)
                .single();
        transactions.executeWithoutResult(status -> events.publishEvent(new UserSignedIn(userId)));
        return userId;
    }

    /** What accepting an invite will do (#87): a member account, and the member's row. */
    private void addMember(UUID groupId, UUID groupLedger, UUID userId) {
        transactions.executeWithoutResult(status -> {
            Account account =
                    ledger.openAccount(new NewAccount(groupLedger, AccountKind.GROUP_MEMBER, null, "Member", EUR));
            jdbc.sql("INSERT INTO group_members (group_id, user_id, account_id) VALUES (:group, :user, :account)")
                    .param("group", groupId)
                    .param("user", userId)
                    .param("account", account.id())
                    .update();
        });
    }

    private UUID memberAccount(UUID groupId, UUID userId) {
        return jdbc.sql("SELECT account_id FROM group_members WHERE group_id = :group AND user_id = :user")
                .param("group", groupId)
                .param("user", userId)
                .query(UUID.class)
                .single();
    }

    private MvcTestResult reverse(Person by, String replacement) {
        return post(
                "/v1/transactions/" + by.transaction() + "/reverse", by.id(), "{\"replacement\": " + replacement + "}");
    }

    private static String expense(UUID categoryId, UUID accountId) {
        return """
                {"type": "EXPENSE", "amount": {"amountMinor": 1250, "currency": "EUR"}, "occurredOn": "%s",
                 "description": "Lunch", "categoryId": "%s", "accountId": "%s"}""".formatted(today(), categoryId, accountId);
    }

    private static String transfer(UUID fromAccountId, UUID toAccountId) {
        return """
                {"type": "TRANSFER", "amount": {"amountMinor": 5000, "currency": "EUR"}, "occurredOn": "%s",
                 "description": "Savings", "fromAccountId": "%s", "toAccountId": "%s"}""".formatted(today(), fromAccountId, toAccountId);
    }

    private static LocalDate today() {
        return LocalDate.now(LISBON);
    }

    private static UUID idOf(MvcTestResult result) {
        assertThat(result.getResponse().getStatus())
                .as("setting up the fixture")
                .isEqualTo(201);
        String id =
                JsonPath.read(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.id");
        return UUID.fromString(id);
    }

    private MvcTestResult get(String uri, UUID userId) {
        return mvc.get().uri(uri).with(user(userId.toString())).exchange();
    }

    /** Every POST gets a new {@code Idempotency-Key}; endpoints that take none ignore it. */
    private MvcTestResult post(String uri, UUID userId, @Nullable String body) {
        var request = mvc.post()
                .uri(uri)
                .with(user(userId.toString()))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private MvcTestResult patch(String uri, UUID userId, String body) {
        return mvc.patch()
                .uri(uri)
                .with(user(userId.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }
}
