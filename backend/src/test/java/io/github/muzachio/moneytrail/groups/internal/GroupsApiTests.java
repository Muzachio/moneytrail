package io.github.muzachio.moneytrail.groups.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.jayway.jsonpath.JsonPath;
import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.groups.GroupCreated;
import io.github.muzachio.moneytrail.groups.MemberLeft;
import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.Ledger;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.DomainException;
import io.github.muzachio.moneytrail.shared.Money;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GET and POST /v1/groups, GET and PATCH /v1/groups/{id} and POST /v1/groups/{id}/leave as in
 * api/openapi.yaml. People join through invites in #87; until then the tests add members the way
 * joining will: a member account in the group's ledger and a row in {@code group_members}.
 */
@AutoConfigureMockMvc
class GroupsApiTests extends PostgresIntegrationTest {

    private static final String GROUPS = "/v1/groups";
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final String EM_SPACE = String.valueOf((char) 0x2003);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private Groups groups;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ConfigurableApplicationContext context;

    private UUID ana;
    private UUID joao;

    @BeforeEach
    void users() {
        ana = newUser("Ana", "https://example.com/ana.png");
        joao = newUser("João", null);
    }

    @Test
    void createsAGroupWithItsLedgerAndTheCreatorsMemberAccountInEur() {
        MvcTestResult created = create(ana, newKey(), """
                {"name": "  Porto weekend  "}""");

        var body = assertThat(created)
                .hasStatus(HttpStatus.CREATED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson();
        body.extractingPath("$.name").isEqualTo("Porto weekend");
        body.extractingPath("$.currency").isEqualTo("EUR");
        body.extractingPath("$.archived").isEqualTo(false);
        body.doesNotHavePath("$.archivedAt");
        body.extractingPath("$.createdAt").isNotNull();
        body.extractingPath("$.members[*].userId")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(ana.toString());

        UUID groupId = idOf(created);
        Ledger groupLedger = ledger.findLedger(LedgerOwner.group(groupId)).orElseThrow();
        List<Account> accounts = ledger.accounts(groupLedger.id());
        assertThat(accounts).singleElement().satisfies(account -> {
            assertThat(account.kind()).isEqualTo(AccountKind.GROUP_MEMBER);
            assertThat(account.currency()).isEqualTo(EUR);
        });
        assertThat(accountOf(groupId, ana)).isEqualTo(accounts.getFirst().id());
    }

    @Test
    void takesTheCurrencyItIsGiven() {
        assertThat(create(ana, newKey(), """
                        {"name": "Flat", "currency": "EUR"}"""))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$.currency")
                .isEqualTo("EUR");
        assertThat(create(ana, newKey(), """
                        {"name": "Flat", "currency": "USD"}""")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aRetriedCreateGivesTheSameGroupAndOpensNothingTwice() {
        String key = newKey();

        UUID first = idOf(create(ana, key, """
                {"name": "Porto weekend"}"""));
        UUID second = idOf(create(ana, key, """
                {"name": "Porto weekend"}"""));

        assertThat(second).isEqualTo(first);
        assertThat(listedIds(ana)).containsExactly(first.toString());
        assertThat(jdbc.sql("SELECT count(*) FROM ledgers WHERE owner_type = 'GROUP' AND owner_id = :id")
                        .param("id", first)
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @Test
    void createNeedsAnIdempotencyKeyAndAName() {
        assertBadRequest(create(ana, null, """
                {"name": "Porto weekend"}"""));
        assertBadRequest(create(ana, newKey(), """
                {"name": "   "}"""));
        // An em space: the schema's pattern only knows ASCII spaces, strip() removes it.
        assertBadRequest(create(ana, newKey(), "{\"name\": \"" + EM_SPACE + "\"}"));
        assertBadRequest(create(ana, newKey(), """
                {}"""));
    }

    @Test
    void listsTheUsersGroupsInTheOrderTheyWereCreatedWithTheirMembers() {
        UUID porto = groupOf(ana, "Porto weekend");
        UUID flat = groupOf(ana, "Flat");
        UUID joaosOwn = groupOf(joao, "Football");
        addMember(flat, joao);

        var list = assertThat(list(ana)).hasStatusOk().bodyJson();
        list.extractingPath("$.items[*].id")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(porto.toString(), flat.toString());
        list.extractingPath("$.items[1].name").isEqualTo("Flat");
        list.extractingPath("$.items[1].currency").isEqualTo("EUR");
        list.extractingPath("$.items[1].memberCount").isEqualTo(2);
        list.extractingPath("$.items[1].archived").isEqualTo(false);
        assertThat(listedIds(joao)).containsExactly(flat.toString(), joaosOwn.toString());
    }

    @Test
    void listsMembersByNameAndPhotoAndNeverByEmail() {
        UUID flat = groupOf(ana, "Flat");
        addMember(flat, joao);

        MvcTestResult group = get(joao, flat);

        var body = assertThat(group).hasStatusOk().bodyJson();
        body.extractingPath("$.members[*].userId")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(ana.toString(), joao.toString());
        body.extractingPath("$.members[0].name").isEqualTo("Ana");
        body.extractingPath("$.members[0].avatarUrl").isEqualTo("https://example.com/ana.png");
        body.extractingPath("$.members[0].joinedAt").isNotNull();
        body.extractingPath("$.members[1].name").isEqualTo("João");
        body.doesNotHavePath("$.members[1].avatarUrl");
        assertThat(body(group)).doesNotContain("@example.com").doesNotContainIgnoringCase("email");
    }

    @Test
    void anyMemberRenamesTheGroup() {
        UUID flat = groupOf(ana, "Flat");
        addMember(flat, joao);

        assertThat(patch(joao, flat, """
                        {"name": "  Flat on Rua Augusta "}"""))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.name")
                .isEqualTo("Flat on Rua Augusta");
        assertThat(get(ana, flat)).bodyJson().extractingPath("$.name").isEqualTo("Flat on Rua Augusta");
    }

    @Test
    void archivesASettledGroupAndRestoresIt() {
        UUID flat = groupOf(ana, "Flat");
        addMember(flat, joao);
        // Joao paid 10.00 for Ana, then Ana paid him back: every balance is zero again.
        post(flat, joao, ana, 1000);
        post(flat, ana, joao, 1000);

        var archived = assertThat(patch(ana, flat, """
                        {"archived": true}""")).hasStatusOk().bodyJson();
        archived.extractingPath("$.archived").isEqualTo(true);
        archived.extractingPath("$.archivedAt").isNotNull();
        assertThat(list(joao)).bodyJson().extractingPath("$.items[0].archived").isEqualTo(true);
        assertThat(list(joao))
                .bodyJson()
                .extractingPath("$.items[0].archivedAt")
                .isNotNull();

        var restored = assertThat(patch(joao, flat, """
                        {"archived": false}""")).hasStatusOk().bodyJson();
        restored.extractingPath("$.archived").isEqualTo(false);
        restored.doesNotHavePath("$.archivedAt");
    }

    @Test
    void aGroupWithABalanceLeftIsNotArchived() {
        UUID flat = groupOf(ana, "Flat");
        addMember(flat, joao);
        post(flat, joao, ana, 1000);

        assertProblem(patch(ana, flat, """
                {"archived": true}"""), HttpStatus.CONFLICT, "group_not_settled");
        assertThat(get(ana, flat)).bodyJson().extractingPath("$.archived").isEqualTo(false);
    }

    @Test
    void anArchivedGroupTakesNoNewExpensesOrPayments() {
        UUID flat = groupOf(ana, "Flat");
        assertThat(groups.forPosting(ana, flat).id()).isEqualTo(flat);

        assertThat(patch(ana, flat, """
                        {"archived": true}""")).hasStatusOk();

        assertThatThrownBy(() -> groups.forPosting(ana, flat))
                .isInstanceOfSatisfying(
                        DomainException.class, e -> assertThat(e.code()).isEqualTo("group_archived"));
        assertThatThrownBy(() -> groups.forPosting(joao, flat))
                .isInstanceOfSatisfying(
                        DomainException.class, e -> assertThat(e.code()).isEqualTo("group_not_found"));
    }

    @Test
    void aPatchNeedsSomethingToChange() {
        UUID flat = groupOf(ana, "Flat");

        assertBadRequest(patch(ana, flat, "{}"));
        assertBadRequest(patch(ana, flat, """
                {"name": " "}"""));
    }

    @Test
    void aSettledMemberLeavesAndKeepsTheirAccountAndHistory() {
        UUID flat = groupOf(ana, "Flat");
        addMember(flat, joao);
        UUID joaosAccount = accountOf(flat, joao);
        post(flat, joao, ana, 1000);
        post(flat, ana, joao, 1000);

        assertThat(leave(joao, flat)).hasStatus(HttpStatus.NO_CONTENT);

        assertNotFound(get(joao, flat));
        assertThat(listedIds(joao)).isEmpty();
        assertThat(get(ana, flat))
                .bodyJson()
                .extractingPath("$.members[*].userId")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(ana.toString());
        assertThat(list(ana))
                .bodyJson()
                .extractingPath("$.items[0].memberCount")
                .isEqualTo(1);
        assertThat(ledger.findAccount(joaosAccount)).isPresent();
        assertThat(accountOf(flat, joao)).isEqualTo(joaosAccount);
        assertThat(jdbc.sql("SELECT left_at IS NOT NULL FROM group_members WHERE group_id = :group AND user_id = :user")
                        .param("group", flat)
                        .param("user", joao)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void aMemberWithABalanceDoesNotLeave() {
        UUID flat = groupOf(ana, "Flat");
        addMember(flat, joao);
        post(flat, joao, ana, 1000);

        assertProblem(leave(joao, flat), HttpStatus.CONFLICT, "member_not_settled");
        assertProblem(leave(ana, flat), HttpStatus.CONFLICT, "member_not_settled");
        assertThat(get(joao, flat)).hasStatusOk();
    }

    @Test
    void someoneOutsideTheGroupGetsNotFoundForEverything() {
        UUID flat = groupOf(ana, "Flat");
        UUID stranger = newUser("Stranger", null);

        assertNotFound(get(stranger, flat));
        assertNotFound(patch(stranger, flat, """
                {"name": "Mine now"}"""));
        assertNotFound(patch(stranger, flat, """
                {"archived": true}"""));
        assertNotFound(leave(stranger, flat));
        assertNotFound(get(ana, UUID.randomUUID()));
        assertThat(get(ana, flat)).bodyJson().extractingPath("$.name").isEqualTo("Flat");
    }

    @Test
    void someoneWhoLeftIsOutsideTheGroup() {
        UUID flat = groupOf(ana, "Flat");
        addMember(flat, joao);
        assertThat(leave(joao, flat)).hasStatus(HttpStatus.NO_CONTENT);

        assertNotFound(get(joao, flat));
        assertNotFound(patch(joao, flat, """
                {"name": "Still mine"}"""));
        assertNotFound(leave(joao, flat));
    }

    @Test
    void groupCreatedAndMemberLeftArePublishedInTheTransactionThatMakesTheChange() {
        List<String> seen = new ArrayList<>();
        Consumer<UUID> creationVisible = groupId -> seen.add(
                "created, row visible: " + (count("SELECT count(*) FROM groups WHERE id = :id", groupId) == 1));
        ApplicationListener<?> onCreated = ApplicationListener.forPayload((GroupCreated event) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            assertThat(event.name()).isEqualTo("Flat");
            assertThat(event.createdBy()).isEqualTo(ana);
            creationVisible.accept(event.groupId());
        });
        ApplicationListener<?> onLeft = ApplicationListener.forPayload((MemberLeft event) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            assertThat(event.userId()).isEqualTo(joao);
            seen.add("left, row updated: "
                    + (count(
                                    "SELECT count(*) FROM group_members WHERE group_id = :id AND left_at IS NOT NULL",
                                    event.groupId())
                            == 1));
        });
        context.addApplicationListener(onCreated);
        context.addApplicationListener(onLeft);
        try {
            UUID flat = groupOf(ana, "Flat");
            addMember(flat, joao);
            assertThat(leave(joao, flat)).hasStatus(HttpStatus.NO_CONTENT);
        } finally {
            context.removeApplicationListener(onCreated);
            context.removeApplicationListener(onLeft);
        }

        // The listeners ran inside the transaction, which already saw its own uncommitted writes.
        assertThat(seen).containsExactly("created, row visible: true", "left, row updated: true");
    }

    private UUID newUser(String name, @Nullable String avatarUrl) {
        return jdbc.sql("INSERT INTO users (email, name, avatar_url) VALUES (:email, :name, :avatar) RETURNING id")
                .param("email", "groups-" + UUID.randomUUID() + "@example.com")
                .param("name", name)
                .param("avatar", avatarUrl)
                .query(UUID.class)
                .single();
    }

    private UUID groupOf(UUID userId, String name) {
        MvcTestResult created = create(userId, newKey(), """
                {"name": "%s"}""".formatted(name));
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return idOf(created);
    }

    /** What accepting an invite will do (#87): a member account, and the member's row. */
    private void addMember(UUID groupId, UUID userId) {
        transactions.executeWithoutResult(status -> {
            Ledger groupLedger = ledger.findLedger(LedgerOwner.group(groupId)).orElseThrow();
            Account account = ledger.openAccount(
                    new NewAccount(groupLedger.id(), AccountKind.GROUP_MEMBER, null, Groups.MEMBER_ACCOUNT, EUR));
            jdbc.sql("INSERT INTO group_members (group_id, user_id, account_id) VALUES (:group, :user, :account)")
                    .param("group", groupId)
                    .param("user", userId)
                    .param("account", account.id())
                    .update();
        });
    }

    /** {@code payer} paid {@code amountMinor} for {@code owes}, as an expense will (#88). */
    private void post(UUID groupId, UUID payer, UUID owes, long amountMinor) {
        ledger.post(new NewJournalEntry(
                ledger.findLedger(LedgerOwner.group(groupId)).orElseThrow().id(),
                LocalDate.now(ZoneOffset.UTC),
                "Test",
                payer,
                List.of(
                        new Posting(accountOf(groupId, payer), new Money(amountMinor, EUR)),
                        new Posting(accountOf(groupId, owes), new Money(-amountMinor, EUR)))));
    }

    private UUID accountOf(UUID groupId, UUID userId) {
        return jdbc.sql("SELECT account_id FROM group_members WHERE group_id = :group AND user_id = :user")
                .param("group", groupId)
                .param("user", userId)
                .query(UUID.class)
                .single();
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    private List<Object> listedIds(UUID userId) {
        return JsonPath.read(body(list(userId)), "$.items[*].id");
    }

    private static UUID idOf(MvcTestResult result) {
        String id = JsonPath.read(body(result), "$.id");
        return UUID.fromString(id);
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private MvcTestResult list(UUID userId) {
        return mvc.get().uri(GROUPS).with(user(userId.toString())).exchange();
    }

    private MvcTestResult get(UUID userId, UUID groupId) {
        return mvc.get()
                .uri(GROUPS + "/" + groupId)
                .with(user(userId.toString()))
                .exchange();
    }

    private MvcTestResult create(UUID userId, @Nullable String idempotencyKey, String body) {
        var request = mvc.post()
                .uri(GROUPS)
                .with(user(userId.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.exchange();
    }

    private MvcTestResult patch(UUID userId, UUID groupId, String body) {
        return mvc.patch()
                .uri(GROUPS + "/" + groupId)
                .with(user(userId.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult leave(UUID userId, UUID groupId) {
        return mvc.post()
                .uri(GROUPS + "/" + groupId + "/leave")
                .with(user(userId.toString()))
                .with(csrf())
                .exchange();
    }

    private static void assertNotFound(MvcTestResult result) {
        assertProblem(result, HttpStatus.NOT_FOUND, "group_not_found");
    }

    private static void assertBadRequest(MvcTestResult result) {
        assertProblem(result, HttpStatus.BAD_REQUEST, "bad_request");
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result)
                .hasStatus(status)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo(code);
    }
}
