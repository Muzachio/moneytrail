package io.github.muzachio.moneytrail.groups.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.jayway.jsonpath.JsonPath;
import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.groups.SettlementEdited;
import io.github.muzachio.moneytrail.groups.SettlementRecorded;
import io.github.muzachio.moneytrail.groups.SettlementReversed;
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
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The balances, settle-up and settlements endpoints of api/openapi.yaml, and the posting rule of
 * ADR-0012 for a payment: who paid {@code +amount}, who was paid {@code −amount}. Members are added
 * the way accepting an invite adds them, as GroupExpensesApiTests does.
 */
@AutoConfigureMockMvc
class SettlementsApiTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 16);

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
    void aSettlementPostsFromPlusAndToMinus() {
        // Ana paid 9.00 for the three of them: she is owed 6.00, João and Pedro owe 3.00 each.
        recordExpense(ana, 900, ana, ana, joao, pedro);

        MvcTestResult recorded = settle(ana, settlement(300, joao, ana));

        var body = assertThat(recorded)
                .hasStatus(HttpStatus.CREATED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson();
        body.extractingPath("$.amount.amountMinor").isEqualTo(300);
        body.extractingPath("$.amount.currency").isEqualTo("EUR");
        body.extractingPath("$.occurredOn").isEqualTo(DAY.toString());
        body.extractingPath("$.from").isEqualTo(joao.toString());
        body.extractingPath("$.to").isEqualTo(ana.toString());
        body.extractingPath("$.recordedBy").isEqualTo(ana.toString());
        body.extractingPath("$.edited").isEqualTo(false);
        body.extractingPath("$.reversed").isEqualTo(false);
        assertThat(ledger.findEntry(idOf(recorded)).orElseThrow().postings())
                .containsExactlyInAnyOrder(posting(joao, 300), posting(ana, -300));
        assertThat(balance(ana)).isEqualTo(300);
        assertThat(balance(joao)).isZero();
        assertThat(balance(pedro)).isEqualTo(-300);
    }

    @Test
    void anyMemberRecordsAnyAmountBetweenTwoOthers() {
        MvcTestResult recorded = settle(pedro, settlement(5000, ana, joao));

        assertThat(recorded).hasStatus(HttpStatus.CREATED);
        assertThat(JsonPath.<String>read(body(recorded), "$.recordedBy")).isEqualTo(pedro.toString());
        assertThat(balance(ana)).isEqualTo(5000);
        assertThat(balance(joao)).isEqualTo(-5000);
    }

    @Test
    void refusesWhatCannotBeASettlement() {
        assertProblem(
                settle(ana, settlement(100, ana, ana)), HttpStatus.UNPROCESSABLE_CONTENT, "group_settlement_to_self");
        assertProblem(
                settle(ana, settlement(100, "USD", DAY, joao, ana)),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "currency_mismatch");
        assertProblem(
                settle(ana, settlement(100, "XYZ", DAY, joao, ana)),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "currency_mismatch");
        assertProblem(settle(ana, settlement(0, joao, ana)), HttpStatus.BAD_REQUEST, "bad_request");
        assertProblem(
                settle(
                        ana,
                        "{\"amount\": {\"amountMinor\": 100, \"currency\": \"EUR\"}, \"occurredOn\": \"%s\", \"from\": \"%s\"}"
                                .formatted(DAY, joao)),
                HttpStatus.BAD_REQUEST,
                "bad_request");
        assertThat(entriesInGroup()).isZero();
    }

    @Test
    void someoneWhoIsNotAMemberNowCannotPayOrBePaid() {
        UUID stranger = newUser("Bea");
        assertThat(leave(pedro)).hasStatus(HttpStatus.NO_CONTENT);

        assertProblem(
                settle(ana, settlement(100, stranger, ana)), HttpStatus.UNPROCESSABLE_CONTENT, "member_not_found");
        assertProblem(
                settle(ana, settlement(100, ana, stranger)), HttpStatus.UNPROCESSABLE_CONTENT, "member_not_found");
        assertProblem(settle(ana, settlement(100, pedro, ana)), HttpStatus.UNPROCESSABLE_CONTENT, "member_not_found");
        assertThat(entriesInGroup()).isZero();
    }

    @Test
    void onlyMembersReachTheBalancesAndTheSettlements() {
        UUID stranger = newUser("Bea");
        UUID settlement = idOf(settle(ana, settlement(100, joao, ana)));

        assertProblem(settle(stranger, settlement(100, joao, ana)), HttpStatus.NOT_FOUND, "group_not_found");
        assertProblem(get(stranger, settlement), HttpStatus.NOT_FOUND, "group_not_found");
        assertProblem(reverse(stranger, settlement, null), HttpStatus.NOT_FOUND, "group_not_found");
        assertProblem(balances(stranger), HttpStatus.NOT_FOUND, "group_not_found");
        assertProblem(settleUp(stranger), HttpStatus.NOT_FOUND, "group_not_found");
        assertProblem(get(ana, UUID.randomUUID()), HttpStatus.NOT_FOUND, "group_settlement_not_found");
    }

    @Test
    void anExpenseIsNotASettlementNorASettlementAnExpense() {
        UUID expense = recordExpense(ana, 900, ana, joao);
        UUID settlement = idOf(settle(ana, settlement(100, joao, ana)));

        assertProblem(get(ana, expense), HttpStatus.NOT_FOUND, "group_settlement_not_found");
        assertProblem(reverse(ana, expense, null), HttpStatus.NOT_FOUND, "group_settlement_not_found");
        assertProblem(
                mvc.get()
                        .uri("/v1/groups/" + group + "/expenses/" + settlement)
                        .with(user(ana.toString()))
                        .exchange(),
                HttpStatus.NOT_FOUND,
                "group_expense_not_found");
    }

    @Test
    void anArchivedGroupTakesNoSettlements() {
        UUID archived = groupOf(ana);
        assertThat(mvc.patch()
                        .uri("/v1/groups/" + archived)
                        .with(user(ana.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .hasStatusOk();

        assertProblem(
                settle(ana, archived, newKey(), settlement(100, ana, joao)), HttpStatus.CONFLICT, "group_archived");
    }

    @Test
    void aRetriedPostGivesTheSameSettlementAndPostsItOnce() {
        String key = newKey();
        String body = settlement(250, joao, ana);

        MvcTestResult first = settle(ana, group, key, body);
        MvcTestResult retry = settle(ana, group, key, body);

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(body(retry)).isEqualTo(body(first));
        assertThat(entriesInGroup()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM settlements WHERE entry_id = :id", idOf(first)))
                .isEqualTo(1);
        assertThat(balance(joao)).isEqualTo(250);
    }

    @Test
    void theGroupsListShowsSettlementsAsSuchNextToTheExpenses() {
        UUID expense = recordExpense(ana, 900, ana, joao, pedro);
        UUID settlement = idOf(settle(ana, settlement(300, joao, ana)));
        UUID reversed = idOf(settle(ana, settlement(100, pedro, ana)));
        assertThat(reverse(ana, reversed, null)).hasStatusOk();

        MvcTestResult list = mvc.get()
                .uri("/v1/groups/" + group + "/expenses")
                .with(user(pedro.toString()))
                .exchange();

        // Same day, so the one recorded last comes first: ids are UUIDv7.
        assertThat(JsonPath.<List<String>>read(body(list), "$.items[*].type")).containsExactly("SETTLEMENT", "EXPENSE");
        assertThat(JsonPath.<String>read(body(list), "$.items[0].settlement.id"))
                .isEqualTo(settlement.toString());
        assertThat(JsonPath.<String>read(body(list), "$.items[0].settlement.from"))
                .isEqualTo(joao.toString());
        assertThat(JsonPath.<String>read(body(list), "$.items[0].settlement.to"))
                .isEqualTo(ana.toString());
        assertThat(JsonPath.<Map<String, Object>>read(body(list), "$.items[0]")).doesNotContainKey("expense");
        assertThat(JsonPath.<String>read(body(list), "$.items[1].expense.id")).isEqualTo(expense.toString());
        assertThat(JsonPath.<Map<String, Object>>read(body(list), "$.items[1]")).doesNotContainKey("settlement");
    }

    @Test
    void reversingPutsBothBalancesBackAndKeepsBothEntriesInTheTrail() {
        UUID settlement = idOf(settle(ana, settlement(400, joao, ana)));

        var reversed =
                assertThat(reverse(pedro, settlement, null)).hasStatusOk().bodyJson();

        reversed.extractingPath("$.settlement.id").isEqualTo(settlement.toString());
        reversed.extractingPath("$.settlement.reversed").isEqualTo(true);
        reversed.doesNotHavePath("$.currentId");
        reversed.extractingPath("$.trail[*].action")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("RECORDED", "REVERSED");
        reversed.extractingPath("$.trail[*].recordedBy")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(ana.toString(), pedro.toString());
        reversed.extractingPath("$.trail[1].reversesEntryId").isEqualTo(settlement.toString());
        reversed.extractingPath("$.trail[1].amount.amountMinor").isEqualTo(400);
        assertThat(balance(ana)).isZero();
        assertThat(balance(joao)).isZero();
        assertProblem(reverse(ana, settlement, null), HttpStatus.CONFLICT, "group_settlement_already_reversed");
    }

    @Test
    void anyMemberEditsASettlementAndTheTrailShowsWhoMadeEachVersion() {
        UUID settlement = idOf(settle(ana, settlement(400, joao, ana)));

        MvcTestResult edited = reverse(pedro, settlement, "{\"replacement\": " + settlement(450, pedro, ana) + "}");

        var body = assertThat(edited).hasStatusOk().bodyJson();
        UUID correction = UUID.fromString(JsonPath.read(body(edited), "$.settlement.id"));
        body.extractingPath("$.settlement.edited").isEqualTo(true);
        body.extractingPath("$.settlement.from").isEqualTo(pedro.toString());
        body.extractingPath("$.settlement.recordedBy").isEqualTo(pedro.toString());
        body.extractingPath("$.currentId").isEqualTo(correction.toString());
        body.extractingPath("$.trail[*].action")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("RECORDED", "REVERSED", "CORRECTED");
        assertThat(balance(joao)).isZero();
        assertThat(balance(pedro)).isEqualTo(450);
        assertThat(balance(ana)).isEqualTo(-450);

        var old = assertThat(get(joao, settlement)).hasStatusOk().bodyJson();
        old.extractingPath("$.settlement.reversed").isEqualTo(true);
        old.extractingPath("$.currentId").isEqualTo(correction.toString());
        assertProblem(
                reverse(ana, settlement, "{\"replacement\": " + settlement(450, pedro, ana) + "}"),
                HttpStatus.CONFLICT,
                "group_settlement_already_reversed");
    }

    @Test
    void aCorrectionThatCannotBeMadeChangesNothing() {
        UUID settlement = idOf(settle(ana, settlement(400, joao, ana)));

        assertProblem(
                reverse(ana, settlement, "{\"replacement\": " + settlement(400, joao, joao) + "}"),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "group_settlement_to_self");
        assertProblem(
                reverse(ana, settlement, "{\"replacement\": " + settlement(400, newUser("Bea"), ana) + "}"),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "member_not_found");

        assertThat(entriesInGroup()).isEqualTo(1);
        assertThat(balance(joao)).isEqualTo(400);
    }

    @Test
    void aSettlementFromOrToSomeoneWhoLeftCannotBeChanged() {
        UUID settlement = idOf(settle(ana, settlement(100, joao, ana)));
        // Ana paid 1.00 for João: both are back to zero, so João can leave.
        recordExpense(ana, 100, ana, joao);
        assertThat(leave(joao)).hasStatus(HttpStatus.NO_CONTENT);

        assertProblem(reverse(ana, settlement, null), HttpStatus.CONFLICT, "group_settlement_member_left");
        assertThat(balance(ana)).isZero();
    }

    @Test
    void balancesListEveryCurrentMemberInTheOrderTheyJoinedAndAddUpToZero() {
        recordExpense(ana, 900, ana, ana, joao, pedro);
        settle(ana, settlement(100, pedro, ana));

        MvcTestResult balances = balances(joao);

        assertThat(balances).hasStatusOk();
        assertThat(JsonPath.<List<String>>read(body(balances), "$.items[*].userId"))
                .containsExactly(ana.toString(), joao.toString(), pedro.toString());
        assertThat(JsonPath.<List<Number>>read(body(balances), "$.items[*].balance.amountMinor"))
                .extracting(Number::longValue)
                .containsExactly(500L, -300L, -200L);
        assertThat(JsonPath.<List<String>>read(body(balances), "$.items[*].balance.currency"))
                .containsOnly("EUR");
    }

    @Test
    void recordingEverySuggestionSettlesTheGroup() {
        recordExpense(ana, 1000, ana, ana, joao, pedro);
        recordExpense(joao, 2000, joao, ana, joao);
        recordExpense(pedro, 700, pedro, ana, joao, pedro);

        String suggestions = body(settleUp(pedro));
        List<String> from = JsonPath.read(suggestions, "$.items[*].from");
        List<String> to = JsonPath.read(suggestions, "$.items[*].to");
        List<Number> amounts = JsonPath.read(suggestions, "$.items[*].amount.amountMinor");
        assertThat(from).isNotEmpty().hasSizeLessThanOrEqualTo(2);
        for (int i = 0; i < from.size(); i++) {
            assertThat(settle(
                            ana,
                            settlement(
                                    amounts.get(i).longValue(),
                                    UUID.fromString(from.get(i)),
                                    UUID.fromString(to.get(i)))))
                    .hasStatus(HttpStatus.CREATED);
        }

        assertThat(JsonPath.<List<Number>>read(body(balances(ana)), "$.items[*].balance.amountMinor"))
                .extracting(Number::longValue)
                .containsOnly(0L);
        assertThat(JsonPath.<List<Object>>read(body(settleUp(ana)), "$.items")).isEmpty();
    }

    @Test
    void theGroupsListShowsTheUsersBalanceInEachGroup() {
        recordExpense(ana, 900, ana, ana, joao, pedro);
        UUID other = groupOf(joao);

        MvcTestResult list =
                mvc.get().uri("/v1/groups").with(user(joao.toString())).exchange();

        assertThat(JsonPath.<List<String>>read(body(list), "$.items[*].id"))
                .containsExactly(group.toString(), other.toString());
        assertThat(JsonPath.<List<Number>>read(body(list), "$.items[*].myBalance.amountMinor"))
                .extracting(Number::longValue)
                .containsExactly(-300L, 0L);
        assertThat(JsonPath.<List<String>>read(body(list), "$.items[*].myBalance.currency"))
                .containsOnly("EUR");
    }

    @Test
    void publishesEachChangeInsideTheTransactionThatMakesIt() {
        List<String> seen = new ArrayList<>();
        ApplicationListener<?> onRecorded = ApplicationListener.forPayload((SettlementRecorded event) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            assertThat(event.groupId()).isEqualTo(group);
            assertThat(event.recordedBy()).isEqualTo(ana);
            seen.add("recorded " + name(event.from()) + " → " + name(event.to()) + " "
                    + event.amount().minor()
                    + ", row visible: "
                    + (count("SELECT count(*) FROM settlements WHERE entry_id = :id", event.settlementId()) > 0));
        });
        ApplicationListener<?> onEdited = ApplicationListener.forPayload((SettlementEdited event) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            assertThat(event.editedBy()).isEqualTo(pedro);
            seen.add("edited " + event.previousAmount().minor() + " → "
                    + event.amount().minor());
        });
        ApplicationListener<?> onReversed = ApplicationListener.forPayload((SettlementReversed event) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            assertThat(event.reversedBy()).isEqualTo(joao);
            seen.add("reversed " + name(event.from()) + " → " + name(event.to()) + " "
                    + event.amount().minor());
        });
        context.addApplicationListener(onRecorded);
        context.addApplicationListener(onEdited);
        context.addApplicationListener(onReversed);
        try {
            UUID settlement = idOf(settle(ana, settlement(300, joao, ana)));
            MvcTestResult edited = reverse(pedro, settlement, "{\"replacement\": " + settlement(350, joao, ana) + "}");
            UUID correction = UUID.fromString(JsonPath.read(body(edited), "$.settlement.id"));
            assertThat(reverse(joao, correction, null)).hasStatusOk();
        } finally {
            context.removeApplicationListener(onRecorded);
            context.removeApplicationListener(onEdited);
            context.removeApplicationListener(onReversed);
        }

        assertThat(seen)
                .containsExactly(
                        "recorded João → Ana 300, row visible: true", "edited 300 → 350", "reversed João → Ana 350");
    }

    /** An expense of {@code amountMinor} that {@code paidBy} paid, split equally among {@code members}. */
    private UUID recordExpense(UUID by, long amountMinor, UUID paidBy, UUID... members) {
        List<String> split = new ArrayList<>();
        for (UUID member : members) {
            split.add("{\"userId\": \"%s\"}".formatted(member));
        }
        MvcTestResult recorded = mvc.post()
                .uri("/v1/groups/" + group + "/expenses")
                .with(user(by.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": {"amountMinor": %d, "currency": "EUR"}, "occurredOn": "%s", "description": "Dinner",
                         "paidBy": "%s", "split": {"method": "EQUALLY", "members": [%s]}}""".formatted(amountMinor, DAY, paidBy, String.join(", ", split)))
                .exchange();
        assertThat(recorded).hasStatus(HttpStatus.CREATED);
        return idOf(recorded);
    }

    private MvcTestResult settle(UUID userId, String body) {
        return settle(userId, group, newKey(), body);
    }

    private MvcTestResult settle(UUID userId, UUID groupId, String idempotencyKey, String body) {
        return mvc.post()
                .uri("/v1/groups/" + groupId + "/settlements")
                .with(user(userId.toString()))
                .with(csrf())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult reverse(UUID userId, UUID settlementId, @Nullable String body) {
        var request = mvc.post()
                .uri("/v1/groups/" + group + "/settlements/" + settlementId + "/reverse")
                .with(user(userId.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private MvcTestResult get(UUID userId, UUID settlementId) {
        return mvc.get()
                .uri("/v1/groups/" + group + "/settlements/" + settlementId)
                .with(user(userId.toString()))
                .exchange();
    }

    private MvcTestResult balances(UUID userId) {
        return mvc.get()
                .uri("/v1/groups/" + group + "/balances")
                .with(user(userId.toString()))
                .exchange();
    }

    private MvcTestResult settleUp(UUID userId) {
        return mvc.get()
                .uri("/v1/groups/" + group + "/settle-up")
                .with(user(userId.toString()))
                .exchange();
    }

    private MvcTestResult leave(UUID userId) {
        return mvc.post()
                .uri("/v1/groups/" + group + "/leave")
                .with(user(userId.toString()))
                .with(csrf())
                .exchange();
    }

    private static String settlement(long amountMinor, UUID from, UUID to) {
        return settlement(amountMinor, "EUR", DAY, from, to);
    }

    private static String settlement(long amountMinor, String currency, LocalDate day, UUID from, UUID to) {
        return """
                {"amount": {"amountMinor": %d, "currency": "%s"}, "occurredOn": "%s", "from": "%s", "to": "%s"}""".formatted(amountMinor, currency, day, from, to);
    }

    private String name(UUID userId) {
        return jdbc.sql("SELECT name FROM users WHERE id = :id")
                .param("id", userId)
                .query(String.class)
                .single();
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
                .param("email", "settlements-" + UUID.randomUUID() + "@example.com")
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

    /** What accepting an invite does (#87): a member account, and the member's row. */
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

    private static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result)
                .hasStatus(status)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo(code);
    }
}
