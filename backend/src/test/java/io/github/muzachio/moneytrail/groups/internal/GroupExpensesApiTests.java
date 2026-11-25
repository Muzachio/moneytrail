package io.github.muzachio.moneytrail.groups.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.jayway.jsonpath.JsonPath;
import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.groups.GroupExpenseAdded;
import io.github.muzachio.moneytrail.groups.GroupExpenseEdited;
import io.github.muzachio.moneytrail.groups.GroupExpenseReversed;
import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.Ledger;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
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
 * The group expenses endpoints of api/openapi.yaml, and the posting rule of ADR-0012: who paid
 * {@code +total}, each person in the split {@code −share}. People join through invites in #87; until
 * then the tests add members the way joining will, as GroupsApiTests does.
 */
@AutoConfigureMockMvc
class GroupExpensesApiTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 14);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ConfigurableApplicationContext context;

    private UUID ana;
    private UUID joao;
    private UUID pedro;
    private UUID group;

    @BeforeEach
    void groupOfThree() {
        ana = newUser("Ana");
        joao = newUser("João");
        pedro = newUser("Pedro");
        group = groupOf(ana);
        addMember(group, joao);
        addMember(group, pedro);
    }

    @Test
    void anEqualSplitGivesTheCentLeftOverToTheFirstIdAsTextAndPostsByTheAdrRule() {
        MvcTestResult recorded = record(ana, expense(1000, ana, "EQUALLY", member(ana), member(joao), member(pedro)));

        var body = assertThat(recorded)
                .hasStatus(HttpStatus.CREATED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson();
        body.extractingPath("$.amount.amountMinor").isEqualTo(1000);
        body.extractingPath("$.amount.currency").isEqualTo("EUR");
        body.extractingPath("$.occurredOn").isEqualTo(DAY.toString());
        body.extractingPath("$.description").isEqualTo("Dinner");
        body.extractingPath("$.paidBy").isEqualTo(ana.toString());
        body.extractingPath("$.recordedBy").isEqualTo(ana.toString());
        body.extractingPath("$.edited").isEqualTo(false);
        body.extractingPath("$.reversed").isEqualTo(false);
        body.extractingPath("$.split.method").isEqualTo("EQUALLY");
        List<UUID> byText = byIdAsText(ana, joao, pedro);
        body.extractingPath("$.split.members[*].userId")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(byText.stream().map(UUID::toString).toArray());
        body.extractingPath("$.split.members[*].value")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(1, 1, 1);
        body.extractingPath("$.split.members[*].share.amountMinor")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(334, 333, 333);

        Map<UUID, Long> shares = Map.of(byText.get(0), 334L, byText.get(1), 333L, byText.get(2), 333L);
        assertThat(ledger.findEntry(idOf(recorded)).orElseThrow().postings())
                .containsExactlyInAnyOrder(
                        posting(ana, 1000),
                        posting(ana, -shares.getOrDefault(ana, 0L)),
                        posting(joao, -shares.getOrDefault(joao, 0L)),
                        posting(pedro, -shares.getOrDefault(pedro, 0L)));
        assertThat(balance(ana)).isEqualTo(1000 - shares.getOrDefault(ana, 0L));
        assertThat(balance(joao)).isEqualTo(-shares.getOrDefault(joao, 0L));
        assertThat(balance(pedro)).isEqualTo(-shares.getOrDefault(pedro, 0L));
    }

    @Test
    void splitsByShares() {
        MvcTestResult recorded = record(ana, expense(900, joao, "SHARES", member(ana, 2), member(pedro, 1)));

        assertThat(recorded).hasStatus(HttpStatus.CREATED);
        assertThat(JsonPath.<String>read(body(recorded), "$.split.method")).isEqualTo("SHARES");
        assertThat(sharesOf(recorded, "$")).containsExactlyInAnyOrderEntriesOf(Map.of(ana, 600L, pedro, 300L));
        assertThat(balance(joao)).isEqualTo(900);
        assertThat(balance(ana)).isEqualTo(-600);
        assertThat(balance(pedro)).isEqualTo(-300);
    }

    @Test
    void splitsByPercentagesInBasisPoints() {
        MvcTestResult recorded = record(
                ana, expense(1000, ana, "PERCENTAGES", member(ana, 5000), member(joao, 3000), member(pedro, 2000)));

        assertThat(recorded).hasStatus(HttpStatus.CREATED);
        assertThat(JsonPath.<String>read(body(recorded), "$.split.method")).isEqualTo("PERCENTAGES");
        assertThat(sharesOf(recorded, "$"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(ana, 500L, joao, 300L, pedro, 200L));
        assertThat(balance(ana)).isEqualTo(500);
    }

    @Test
    void splitsByExactAmounts() {
        MvcTestResult recorded =
                record(ana, expense(1000, pedro, "EXACT_AMOUNTS", member(ana, 700), member(joao, 300)));

        assertThat(recorded).hasStatus(HttpStatus.CREATED);
        assertThat(JsonPath.<String>read(body(recorded), "$.split.method")).isEqualTo("EXACT_AMOUNTS");
        assertThat(sharesOf(recorded, "$")).containsExactlyInAnyOrderEntriesOf(Map.of(ana, 700L, joao, 300L));
        assertThat(balance(pedro)).isEqualTo(1000);
        assertThat(balance(ana)).isEqualTo(-700);
        assertThat(balance(joao)).isEqualTo(-300);
    }

    @Test
    void aPayerOutsideTheSplitIsOwedTheWholeAmount() {
        UUID expense = idOf(record(ana, expense(1250, joao, "EQUALLY", member(ana))));

        assertThat(ledger.findEntry(expense).orElseThrow().postings())
                .containsExactlyInAnyOrder(posting(joao, 1250), posting(ana, -1250));
        assertThat(balance(joao)).isEqualTo(1250);
        assertThat(balance(pedro)).isZero();
    }

    @Test
    void aShareOfZeroIsKeptInTheSplitButPostsNothing() {
        UUID expense = idOf(record(ana, expense(2, ana, "EQUALLY", member(ana), member(joao), member(pedro))));

        UUID last = byIdAsText(ana, joao, pedro).getLast();
        assertThat(sharesOf(get(ana, expense), "$.expense"))
                .containsEntry(last, 0L)
                .hasSize(3);
        assertThat(ledger.findEntry(expense).orElseThrow().postings())
                .hasSize(3)
                .noneMatch(posting -> posting.accountId().equals(accountOf(group, last)));
        assertThat(count("SELECT count(*) FROM expense_splits WHERE entry_id = :id", expense))
                .isEqualTo(3);
    }

    @Test
    void refusesSplitsThatCannotBeMade() {
        assertRefused(record(ana, expense(1000, ana, "EQUALLY", member(ana), member(ana))), "split_member_twice");
        assertRefused(
                record(ana, expense(1000, ana, "PERCENTAGES", member(ana, 5000), member(joao, 4000))),
                "split_percentages_not_100");
        assertRefused(
                record(ana, expense(1000, ana, "EXACT_AMOUNTS", member(ana, 500), member(joao, 400))),
                "split_amounts_not_total");
        assertRefused(
                record(ana, expense(1000, ana, "SHARES", member(ana, 10_000), member(joao, 1))),
                "split_too_many_parts");
        assertThat(entriesInGroup()).isZero();
    }

    @Test
    void refusesAnAmountInAnotherCurrency() {
        assertRefused(record(ana, expenseIn("USD", 1000, ana, "EQUALLY", member(ana))), "currency_mismatch");
        assertRefused(record(ana, expenseIn("XYZ", 1000, ana, "EQUALLY", member(ana))), "currency_mismatch");
        assertThat(entriesInGroup()).isZero();
    }

    @Test
    void someoneWhoIsNotAMemberNowCannotPayOrBeInTheSplit() {
        UUID stranger = newUser("Bea");
        UUID rita = newUser("Rita");
        addMember(group, rita);
        assertThat(leave(rita)).hasStatus(HttpStatus.NO_CONTENT);

        assertRefused(record(ana, expense(1000, stranger, "EQUALLY", member(ana))), "member_not_found");
        assertRefused(record(ana, expense(1000, ana, "EQUALLY", member(ana), member(rita))), "member_not_found");
        assertRefused(record(ana, expense(1000, ana, "EQUALLY", member(UUID.randomUUID()))), "member_not_found");
        assertThat(entriesInGroup()).isZero();
    }

    @Test
    void refusesBadRequestsWithTheSchemasCode() {
        assertBadRequest(record(ana, expense(1000, ana, "EQUALLY", member(ana, 1))));
        assertBadRequest(record(ana, expense(1000, ana, "SHARES", member(ana))));
        assertBadRequest(record(ana, expense(0, ana, "EQUALLY", member(ana))));
        assertBadRequest(record(ana, expense(1000, ana, "EQUALLY")));
        assertBadRequest(record(ana, """
                {"amount": {"amountMinor": 1000, "currency": "EUR"}, "occurredOn": "%s", "description": "\\u00a0 ",
                 "paidBy": "%s", "split": {"method": "EQUALLY", "members": [{"userId": "%s"}]}}""".formatted(DAY, ana, ana)));
        assertThat(entriesInGroup()).isZero();
    }

    @Test
    void anArchivedGroupTakesNoExpenses() {
        UUID archived = groupOf(ana);
        assertThat(mvc.patch()
                        .uri("/v1/groups/" + archived)
                        .with(user(ana.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .hasStatusOk();

        assertProblem(
                record(ana, archived, newKey(), expense(1000, ana, "EQUALLY", member(ana))),
                HttpStatus.CONFLICT,
                "group_archived");
    }

    @Test
    void onlyMembersReachTheExpenses() {
        UUID stranger = newUser("Bea");
        UUID expense = idOf(record(ana, expense(1000, ana, "EQUALLY", member(ana), member(joao))));

        assertProblem(
                record(stranger, expense(1000, stranger, "EQUALLY", member(stranger))),
                HttpStatus.NOT_FOUND,
                "group_not_found");
        assertProblem(get(stranger, expense), HttpStatus.NOT_FOUND, "group_not_found");
        assertProblem(list(stranger, null), HttpStatus.NOT_FOUND, "group_not_found");
        assertProblem(reverse(stranger, expense, null), HttpStatus.NOT_FOUND, "group_not_found");
        assertProblem(get(ana, UUID.randomUUID()), HttpStatus.NOT_FOUND, "group_expense_not_found");
    }

    @Test
    void aRetriedPostGivesTheSameExpenseAndPostsItOnce() {
        String key = newKey();
        String body = expense(1000, ana, "EQUALLY", member(ana), member(joao));

        MvcTestResult first = record(ana, group, key, body);
        MvcTestResult retry = record(ana, group, key, body);

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(body(retry)).isEqualTo(body(first));
        assertThat(entriesInGroup()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM expense_splits WHERE entry_id = :id", idOf(first)))
                .isEqualTo(2);
        assertThat(balance(ana)).isEqualTo(500);
    }

    @Test
    void reversingPutsEveryBalanceBackAndKeepsBothEntriesInTheTrail() {
        UUID expense = idOf(record(ana, expense(1000, ana, "EQUALLY", member(ana), member(joao))));

        var reversed = assertThat(reverse(joao, expense, null)).hasStatusOk().bodyJson();

        reversed.extractingPath("$.expense.id").isEqualTo(expense.toString());
        reversed.extractingPath("$.expense.reversed").isEqualTo(true);
        reversed.doesNotHavePath("$.currentId");
        reversed.extractingPath("$.trail[*].action")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("RECORDED", "REVERSED");
        reversed.extractingPath("$.trail[*].recordedBy")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(ana.toString(), joao.toString());
        reversed.extractingPath("$.trail[1].reversesEntryId").isEqualTo(expense.toString());
        reversed.extractingPath("$.trail[1].amount.amountMinor").isEqualTo(1000);
        assertThat(balance(ana)).isZero();
        assertThat(balance(joao)).isZero();
        assertThat(listedIds(list(ana, null))).isEmpty();

        assertProblem(reverse(ana, expense, null), HttpStatus.CONFLICT, "group_expense_already_reversed");
    }

    @Test
    void anyMemberEditsAnExpenseAndTheTrailShowsWhoMadeEachVersion() {
        UUID expense = idOf(record(ana, expense(1000, ana, "EQUALLY", member(ana), member(joao))));

        MvcTestResult result = reverse(
                pedro,
                expense,
                "{\"replacement\": " + expense(1200, joao, "EXACT_AMOUNTS", member(ana, 200), member(pedro, 1000))
                        + "}");

        var edited = assertThat(result).hasStatusOk().bodyJson();
        UUID correction = UUID.fromString(JsonPath.read(body(result), "$.expense.id"));
        edited.extractingPath("$.expense.edited").isEqualTo(true);
        edited.extractingPath("$.expense.reversed").isEqualTo(false);
        edited.extractingPath("$.expense.recordedBy").isEqualTo(pedro.toString());
        edited.extractingPath("$.expense.paidBy").isEqualTo(joao.toString());
        edited.extractingPath("$.currentId").isEqualTo(correction.toString());
        edited.extractingPath("$.trail[*].action")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("RECORDED", "REVERSED", "CORRECTED");
        edited.extractingPath("$.trail[*].recordedBy")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(ana.toString(), pedro.toString(), pedro.toString());
        edited.extractingPath("$.trail[*].amount.amountMinor")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(1000, 1000, 1200);
        assertThat(balance(ana)).isEqualTo(-200);
        assertThat(balance(joao)).isEqualTo(1200);
        assertThat(balance(pedro)).isEqualTo(-1000);

        var old = assertThat(get(ana, expense)).hasStatusOk().bodyJson();
        old.extractingPath("$.expense.reversed").isEqualTo(true);
        old.extractingPath("$.currentId").isEqualTo(correction.toString());
        assertThat(listedIds(list(ana, null))).containsExactly(correction.toString());
        assertProblem(reverse(ana, expense, null), HttpStatus.CONFLICT, "group_expense_already_reversed");
    }

    @Test
    void aCorrectionThatCannotBeMadeChangesNothing() {
        UUID expense = idOf(record(ana, expense(1000, ana, "EQUALLY", member(ana), member(joao))));

        assertRefused(
                reverse(ana, expense, "{\"replacement\": " + expense(1000, ana, "PERCENTAGES", member(ana, 10)) + "}"),
                "split_percentages_not_100");
        assertRefused(
                reverse(
                        ana,
                        expense,
                        "{\"replacement\": " + expense(1000, newUser("Bea"), "EQUALLY", member(ana)) + "}"),
                "member_not_found");

        assertThat(entriesInGroup()).isEqualTo(1);
        assertThat(balance(ana)).isEqualTo(500);
        assertThat(JsonPath.<Boolean>read(body(get(ana, expense)), "$.expense.reversed"))
                .isFalse();
    }

    @Test
    void anExpenseThatMovesTheBalanceOfSomeoneWhoLeftCannotBeChanged() {
        UUID owedByJoao = idOf(record(ana, expense(1000, ana, "EQUALLY", member(joao))));
        UUID joaosOwn = idOf(record(ana, expense(1000, joao, "EQUALLY", member(ana), member(pedro))));
        // −1000 + 1000, then two more that cancel out: João's balance is zero, so he can leave.
        assertThat(record(joao, expense(1000, joao, "EQUALLY", member(ana)))).hasStatus(HttpStatus.CREATED);
        assertThat(record(ana, expense(1000, ana, "EQUALLY", member(joao)))).hasStatus(HttpStatus.CREATED);
        assertThat(balance(joao)).isZero();
        assertThat(leave(joao)).hasStatus(HttpStatus.NO_CONTENT);

        assertProblem(reverse(ana, owedByJoao, null), HttpStatus.CONFLICT, "group_expense_member_left");
        assertProblem(reverse(pedro, joaosOwn, null), HttpStatus.CONFLICT, "group_expense_member_left");
        // Who left keeps their part in past expenses, which every member can still read.
        assertThat(get(ana, owedByJoao)).hasStatusOk();
    }

    @Test
    void anArchivedGroupTakesNoCorrections() {
        UUID expense = idOf(record(ana, expense(1000, ana, "EQUALLY", member(ana))));
        assertThat(mvc.patch()
                        .uri("/v1/groups/" + group)
                        .with(user(ana.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .hasStatusOk();

        assertProblem(reverse(ana, expense, null), HttpStatus.CONFLICT, "group_archived");
    }

    @Test
    void listsTheCurrentVersionsNewestFirstAcrossPages() {
        UUID older = idOf(record(ana, expenseOn(DAY.minusDays(1), 100, ana, "EQUALLY", member(ana))));
        UUID first = idOf(record(ana, expenseOn(DAY, 200, ana, "EQUALLY", member(ana))));
        UUID second = idOf(record(ana, expenseOn(DAY, 300, ana, "EQUALLY", member(ana))));
        UUID reversed = idOf(record(ana, expenseOn(DAY.plusDays(1), 400, ana, "EQUALLY", member(ana))));
        assertThat(reverse(ana, reversed, null)).hasStatusOk();

        MvcTestResult page1 = list(ana, null, 2);
        String cursor = JsonPath.read(body(page1), "$.nextCursor");
        MvcTestResult page2 = list(ana, cursor, 2);

        // Within a day the one recorded last comes first: ids are UUIDv7.
        assertThat(listedIds(page1)).containsExactly(second.toString(), first.toString());
        assertThat(listedIds(page2)).containsExactly(older.toString());
        assertThat(JsonPath.<Map<String, Object>>read(body(page2), "$")).doesNotContainKey("nextCursor");
        assertBadRequest(list(ana, "not-a-cursor", 2));
    }

    @Test
    void publishesEachChangeInsideTheTransactionThatMakesIt() {
        List<String> seen = new ArrayList<>();
        ApplicationListener<?> onAdded = ApplicationListener.forPayload((GroupExpenseAdded event) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            assertThat(event.groupId()).isEqualTo(group);
            assertThat(event.paidBy()).isEqualTo(joao);
            assertThat(event.recordedBy()).isEqualTo(ana);
            seen.add("added " + event.description() + " " + event.amount().minor() + ", entry visible: "
                    + (count("SELECT count(*) FROM expense_splits WHERE entry_id = :id", event.expenseId()) > 0));
        });
        ApplicationListener<?> onEdited = ApplicationListener.forPayload((GroupExpenseEdited event) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            assertThat(event.editedBy()).isEqualTo(pedro);
            seen.add("edited " + event.previousDescription() + " "
                    + event.previousAmount().minor() + " → " + event.description() + " "
                    + event.amount().minor());
        });
        ApplicationListener<?> onReversed = ApplicationListener.forPayload((GroupExpenseReversed event) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            assertThat(event.reversedBy()).isEqualTo(joao);
            seen.add("reversed " + event.description() + " " + event.amount().minor());
        });
        context.addApplicationListener(onAdded);
        context.addApplicationListener(onEdited);
        context.addApplicationListener(onReversed);
        try {
            UUID expense = idOf(record(ana, expense(1000, joao, "EQUALLY", member(ana), member(joao))));
            MvcTestResult edited = reverse(
                    pedro,
                    expense,
                    "{\"replacement\": " + expenseFor("Lunch", 1500, joao, "EQUALLY", member(ana)) + "}");
            UUID correction = UUID.fromString(JsonPath.read(body(edited), "$.expense.id"));
            assertThat(reverse(joao, correction, null)).hasStatusOk();
        } finally {
            context.removeApplicationListener(onAdded);
            context.removeApplicationListener(onEdited);
            context.removeApplicationListener(onReversed);
        }

        assertThat(seen)
                .containsExactly(
                        "added Dinner 1000, entry visible: true",
                        "edited Dinner 1000 → Lunch 1500",
                        "reversed Lunch 1500");
    }

    private MvcTestResult record(UUID userId, String body) {
        return record(userId, group, newKey(), body);
    }

    private MvcTestResult record(UUID userId, UUID groupId, String idempotencyKey, String body) {
        return mvc.post()
                .uri("/v1/groups/" + groupId + "/expenses")
                .with(user(userId.toString()))
                .with(csrf())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult reverse(UUID userId, UUID expenseId, @Nullable String body) {
        var request = mvc.post()
                .uri("/v1/groups/" + group + "/expenses/" + expenseId + "/reverse")
                .with(user(userId.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private MvcTestResult get(UUID userId, UUID expenseId) {
        return mvc.get()
                .uri("/v1/groups/" + group + "/expenses/" + expenseId)
                .with(user(userId.toString()))
                .exchange();
    }

    private MvcTestResult list(UUID userId, @Nullable String cursor) {
        return list(userId, cursor, null);
    }

    private MvcTestResult list(UUID userId, @Nullable String cursor, @Nullable Integer limit) {
        String uri = "/v1/groups/" + group + "/expenses?";
        if (cursor != null) {
            uri += "cursor=" + cursor + "&";
        }
        if (limit != null) {
            uri += "limit=" + limit;
        }
        return mvc.get().uri(uri).with(user(userId.toString())).exchange();
    }

    private MvcTestResult leave(UUID userId) {
        return mvc.post()
                .uri("/v1/groups/" + group + "/leave")
                .with(user(userId.toString()))
                .with(csrf())
                .exchange();
    }

    private static String expense(long amountMinor, UUID paidBy, String method, String... members) {
        return expense(amountMinor, "EUR", DAY, "Dinner", paidBy, method, members);
    }

    private static String expenseIn(String currency, long amountMinor, UUID paidBy, String method, String... members) {
        return expense(amountMinor, currency, DAY, "Dinner", paidBy, method, members);
    }

    private static String expenseOn(LocalDate day, long amountMinor, UUID paidBy, String method, String... members) {
        return expense(amountMinor, "EUR", day, "Dinner", paidBy, method, members);
    }

    private static String expenseFor(
            String description, long amountMinor, UUID paidBy, String method, String... members) {
        return expense(amountMinor, "EUR", DAY, description, paidBy, method, members);
    }

    private static String expense(
            long amountMinor,
            String currency,
            LocalDate day,
            String description,
            UUID paidBy,
            String method,
            String... members) {
        return """
                {"amount": {"amountMinor": %d, "currency": "%s"}, "occurredOn": "%s", "description": "%s",
                 "paidBy": "%s", "split": {"method": "%s", "members": [%s]}}""".formatted(amountMinor, currency, day, description, paidBy, method, String.join(", ", members));
    }

    private static String member(UUID userId) {
        return "{\"userId\": \"%s\"}".formatted(userId);
    }

    private static String member(UUID userId, long value) {
        return "{\"userId\": \"%s\", \"value\": %d}".formatted(userId, value);
    }

    /** The shares of the expense at {@code path} in the response, by member. */
    private static Map<UUID, Long> sharesOf(MvcTestResult response, String path) {
        List<String> members = JsonPath.read(body(response), path + ".split.members[*].userId");
        List<Number> shares = JsonPath.read(body(response), path + ".split.members[*].share.amountMinor");
        Map<UUID, Long> byMember = new java.util.HashMap<>();
        for (int i = 0; i < members.size(); i++) {
            byMember.put(UUID.fromString(members.get(i)), shares.get(i).longValue());
        }
        return byMember;
    }

    private static List<UUID> byIdAsText(UUID... ids) {
        return Stream.of(ids).sorted(Comparator.comparing(UUID::toString)).toList();
    }

    private Posting posting(UUID userId, long amountMinor) {
        return new Posting(accountOf(group, userId), new Money(amountMinor, EUR));
    }

    private long balance(UUID userId) {
        return ledger.balance(accountOf(group, userId)).minor();
    }

    private long entriesInGroup() {
        return count("""
                SELECT count(*) FROM journal_entries
                WHERE ledger_id = (SELECT ledger_id FROM groups WHERE id = :id)""", group);
    }

    private UUID newUser(String name) {
        return jdbc.sql("INSERT INTO users (email, name) VALUES (:email, :name) RETURNING id")
                .param("email", "expenses-" + UUID.randomUUID() + "@example.com")
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    private UUID groupOf(UUID userId) {
        MvcTestResult created = mvc.post()
                .uri("/v1/groups")
                .with(user(userId.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Porto weekend\"}")
                .exchange();
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

    private static List<Object> listedIds(MvcTestResult result) {
        return JsonPath.read(body(result), "$.items[*].id");
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

    private static void assertRefused(MvcTestResult result, String code) {
        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, code);
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
