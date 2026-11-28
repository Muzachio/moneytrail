package io.github.muzachio.moneytrail.personal.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.jayway.jsonpath.JsonPath;
import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The mirror of a group in a member's ledger (#94, ADR-0015), through the API: the group's changes are
 * made through the groups endpoints, so the events come from the groups module itself, and the
 * listener records them after each change commits, so the tests wait for it.
 */
@AutoConfigureMockMvc
class GroupMirrorApiTests extends PostgresIntegrationTest {

    /** Today, so the opening balance and the expenses fall in the same month of the summary. */
    private static final LocalDate DAY = LocalDate.now(ZoneId.of("Europe/Lisbon"));

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    private UUID ana;
    private UUID joao;
    private UUID group;
    private UUID checking;
    private Map<String, UUID> anasCategories;

    @BeforeEach
    void anasGroupWhichJoaoJoins() {
        ana = newUser("Ana");
        joao = newUser("João");
        group = newGroup(ana);
        join(joao);
        anasCategories = categoriesByKey(ana);
        checking = newAccount(ana, "CHECKING", "Checking");
    }

    @Test
    void isOffUntilTheMemberTurnsItOnAndOnlyForMembers() {
        assertThat(mirror(ana)).containsEntry("enabled", false).doesNotContainKeys("accountId", "groupAccountId");
        assertProblem(
                mvc.get()
                        .uri(mirrorPath())
                        .with(user(newUser("Bea").toString()))
                        .exchange(),
                HttpStatus.NOT_FOUND,
                "group_not_found");

        Map<String, Object> on = body(assertOk(turnOn(ana, category("other"), checking)));

        assertThat(on)
                .containsEntry("enabled", true)
                .containsEntry("categoryId", category("other").toString())
                .containsEntry("accountId", checking.toString())
                .containsKey("groupAccountId");
        assertThat(mirror(joao)).as("each member decides for themselves").containsEntry("enabled", false);
    }

    /** ADR-0015's rows: what Ana paid, what João paid, and what João paid back. */
    @Test
    void recordsTheSharesOfExpensesAndPaymentsOnceItIsOn() {
        UUID groupAccount = groupAccountOf(turnOn(ana, category("other"), checking));

        UUID dinner = recordExpense(ana, 3000, "Dinner", "food");
        UUID taxi = recordExpense(joao, 1200, "Taxi", "transport");
        recordSettlement(joao, 900, joao, ana);
        awaitListeners();

        List<Map<String, Object>> items = transactions(ana);
        assertThat(items).hasSize(3);
        assertThat(item(items, "Dinner"))
                .containsEntry("type", "EXPENSE")
                .containsEntry("amount", eur(1500))
                .containsEntry("categoryId", category("food").toString())
                .containsEntry("accountId", checking.toString())
                .containsEntry(
                        "group",
                        Map.of(
                                "groupId", group.toString(),
                                "groupAccountId", groupAccount.toString(),
                                "groupEntryId", dinner.toString(),
                                "type", "EXPENSE",
                                "paid", eur(3000)));
        assertThat(item(items, "Taxi"))
                .as("someone else paid: owed to the group, nothing of Ana's moved")
                .containsEntry("type", "EXPENSE")
                .containsEntry("amount", eur(600))
                .containsEntry("categoryId", category("transport").toString())
                .containsEntry("accountId", groupAccount.toString());
        assertThat(JsonPath.<Map<String, Object>>read(item(items, "Taxi"), "$.group"))
                .containsEntry("groupEntryId", taxi.toString())
                .doesNotContainKey("paid");
        Map<String, Object> payment = items.stream()
                .filter(transaction -> "TRANSFER".equals(transaction.get("type")))
                .findFirst()
                .orElseThrow();
        assertThat(payment)
                .as("paid back: a transfer, never spending")
                .containsEntry("amount", eur(900))
                .containsEntry("fromAccountId", groupAccount.toString())
                .containsEntry("toAccountId", checking.toString());

        assertThat(balanceOf(ana, groupAccount)).isEqualTo(groupBalanceOf(ana)).isZero();
        assertThat(balanceOf(ana, checking)).isEqualTo(-3000 + 900);
        assertThat(JsonPath.<Integer>read(transactionsBody(ana), "$.summary.spending.amountMinor"))
                .isEqualTo(1500 + 600);
        assertThat(spendingByCategory(ana))
                .containsEntry(category("food").toString(), 1500)
                .containsEntry(category("transport").toString(), 600);
        Map<String, Object> account = accounts(ana).stream()
                .filter(found -> groupAccount.toString().equals(found.get("id")))
                .findFirst()
                .orElseThrow();
        assertThat(account)
                .containsEntry("type", "GROUP")
                .containsEntry("name", "Porto weekend")
                .containsEntry("groupId", group.toString());
    }

    @Test
    void followsEditsAndReversalsInTheGroup() {
        UUID groupAccount = groupAccountOf(turnOn(ana, category("other"), checking));
        UUID dinner = recordExpense(ana, 3000, "Dinner", "food");
        awaitListeners();

        UUID corrected = editExpense(joao, dinner, 3600, "Dinner and wine", "leisure");
        awaitListeners();

        Map<String, Object> edited = item(transactions(ana), "Dinner and wine");
        assertThat(edited)
                .containsEntry("amount", eur(1800))
                .containsEntry("categoryId", category("leisure").toString())
                .containsEntry("edited", true);
        assertThat(JsonPath.<Map<String, Object>>read(edited, "$.group"))
                .containsEntry("groupEntryId", corrected.toString())
                .containsEntry("paid", eur(3600));
        assertThat(balanceOf(ana, groupAccount)).isEqualTo(groupBalanceOf(ana)).isEqualTo(1800);

        assertOk(mvc.post()
                .uri("/v1/groups/" + group + "/expenses/" + corrected + "/reverse")
                .with(user(joao.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .exchange());
        awaitListeners();

        assertThat(transactions(ana)).isEmpty();
        assertThat(balanceOf(ana, groupAccount)).isEqualTo(groupBalanceOf(ana)).isZero();
        assertThat(balanceOf(ana, checking)).isZero();
    }

    /** From the moment it is turned on: earlier expenses only in the opening balance, never as spending. */
    @Test
    void startsFromTheCurrentBalanceWhenTurnedOnHalfway() {
        recordExpense(joao, 1200, "Taxi", "transport");
        awaitListeners();

        UUID groupAccount = groupAccountOf(turnOn(ana, category("other"), checking));

        assertThat(balanceOf(ana, groupAccount)).isEqualTo(groupBalanceOf(ana)).isEqualTo(-600);
        assertThat(transactions(ana)).isEmpty();
        assertThat(JsonPath.<Integer>read(transactionsBody(ana), "$.summary.spending.amountMinor"))
                .isZero();

        recordExpense(joao, 1000, "Museum", "leisure");
        awaitListeners();

        assertThat(transactions(ana))
                .extracting(transaction -> transaction.get("description"))
                .containsExactly("Museum");
        assertThat(balanceOf(ana, groupAccount)).isEqualTo(groupBalanceOf(ana)).isEqualTo(-1100);
    }

    @Test
    void turningItOffClosesTheAccountAndStopsRecording() {
        UUID groupAccount = groupAccountOf(turnOn(ana, category("other"), checking));
        recordExpense(ana, 3000, "Dinner", "food");
        awaitListeners();

        Map<String, Object> off = body(assertOk(mvc.delete()
                .uri(mirrorPath())
                .with(user(ana.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .exchange()));
        recordExpense(ana, 2000, "Lunch", "food");
        awaitListeners();

        assertThat(off).containsEntry("enabled", false);
        assertThat(balanceOf(ana, groupAccount)).isZero();
        assertThat(accounts(ana))
                .filteredOn(account -> groupAccount.toString().equals(account.get("id")))
                .singleElement()
                .satisfies(account -> assertThat(account).containsEntry("archived", true));
        assertThat(transactions(ana))
                .extracting(transaction -> transaction.get("description"))
                .containsExactly("Dinner");

        assertThat(groupAccountOf(turnOn(ana, category("other"), checking)))
                .as("turning it on again brings the same account back, with the balance now")
                .isEqualTo(groupAccount);
        assertThat(balanceOf(ana, groupAccount)).isEqualTo(groupBalanceOf(ana)).isEqualTo(1500 + 1000);
    }

    @Test
    void theAccountMoneyCameFromCanBeChangedAndEditsKeepIt() {
        turnOn(ana, category("other"), checking);
        UUID savings = newAccount(ana, "SAVINGS", "Savings");
        UUID dinner = recordExpense(ana, 3000, "Dinner", "food");
        awaitListeners();
        String transactionId = (String) item(transactions(ana), "Dinner").get("id");

        String changed = text(assertOk(mvc.post()
                .uri("/v1/transactions/" + transactionId + "/account")
                .with(user(ana.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\": \"%s\"}".formatted(savings))
                .exchange()));

        assertThat(JsonPath.<String>read(changed, "$.transaction.accountId")).isEqualTo(savings.toString());
        assertThat(JsonPath.<List<String>>read(changed, "$.trail[*].action"))
                .containsExactly("RECORDED", "REVERSED", "CORRECTED");
        assertThat(balanceOf(ana, checking)).isZero();
        assertThat(balanceOf(ana, savings)).isEqualTo(-3000);

        editExpense(joao, dinner, 3600, "Dinner and wine", "food");
        awaitListeners();

        assertThat(item(transactions(ana), "Dinner and wine")).containsEntry("accountId", savings.toString());
        assertThat(balanceOf(ana, savings)).isEqualTo(-3600);
    }

    @Test
    void itsTransactionsAndItsAccountAreOnlyChangedThroughTheMirror() {
        UUID groupAccount = groupAccountOf(turnOn(ana, category("other"), checking));
        recordExpense(ana, 3000, "Dinner", "food");
        recordExpense(joao, 1200, "Taxi", "transport");
        awaitListeners();
        List<Map<String, Object>> items = transactions(ana);

        assertProblem(
                mvc.post()
                        .uri("/v1/transactions/" + item(items, "Dinner").get("id") + "/reverse")
                        .with(user(ana.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", newKey())
                        .exchange(),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "transaction_from_group");
        assertProblem(
                mvc.post()
                        .uri("/v1/transactions/" + item(items, "Taxi").get("id") + "/account")
                        .with(user(ana.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\": \"%s\"}".formatted(checking))
                        .exchange(),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "account_change_refused");
        assertProblem(
                mvc.post()
                        .uri("/v1/transactions")
                        .with(user(ana.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "EXPENSE", "amount": {"amountMinor": 500, "currency": "EUR"},
                                 "occurredOn": "%s", "description": "Coffee", "categoryId": "%s", "accountId": "%s"}""".formatted(DAY, category("food"), groupAccount))
                        .exchange(),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "account_from_group");
        assertProblem(
                mvc.post()
                        .uri("/v1/accounts")
                        .with(user(ana.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Mine\", \"type\": \"GROUP\", \"currency\": \"EUR\"}")
                        .exchange(),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "account_from_group");
        for (String change : List.of("{\"archived\": true}", "{\"type\": \"SAVINGS\"}")) {
            assertProblem(
                    mvc.patch()
                            .uri("/v1/accounts/" + groupAccount)
                            .with(user(ana.toString()))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(change)
                            .exchange(),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "account_from_group");
        }
    }

    @Test
    void refusesACategoryOrAnAccountItCannotUse() {
        UUID salary = category("salary");
        assertProblem(turnOn(ana, salary, checking), HttpStatus.UNPROCESSABLE_CONTENT, "category_kind_mismatch");
        assertProblem(turnOn(ana, UUID.randomUUID(), checking), HttpStatus.UNPROCESSABLE_CONTENT, "category_not_found");
        assertProblem(
                turnOn(ana, category("other"), category("food")),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "account_not_found");
        assertOk(mvc.patch()
                .uri("/v1/accounts/" + checking)
                .with(user(ana.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"archived\": true}")
                .exchange());
        assertProblem(turnOn(ana, category("other"), checking), HttpStatus.UNPROCESSABLE_CONTENT, "account_archived");
    }

    @Test
    void aShareGoesToTheMirrorsCategoryWhenTheMemberHasNoneWithTheExpensesKey() {
        assertOk(mvc.patch()
                .uri("/v1/categories/" + category("health"))
                .with(user(ana.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"archived\": true}")
                .exchange());
        turnOn(ana, category("other"), checking);

        recordExpense(ana, 2000, "Pharmacy", "health");
        awaitListeners();

        assertThat(item(transactions(ana), "Pharmacy"))
                .containsEntry("categoryId", category("other").toString());
    }

    @Test
    void leavingTheGroupTurnsItOff() {
        UUID joaosChecking = newAccount(joao, "CHECKING", "Checking");
        UUID groupAccount = groupAccountOf(
                turnOn(joao, Objects.requireNonNull(categoriesByKey(joao).get("other")), joaosChecking));

        assertThat(mvc.post()
                        .uri("/v1/groups/" + group + "/leave")
                        .with(user(joao.toString()))
                        .with(csrf())
                        .exchange())
                .hasStatus(HttpStatus.NO_CONTENT);
        awaitListeners();

        assertThat(accounts(joao))
                .filteredOn(account -> groupAccount.toString().equals(account.get("id")))
                .singleElement()
                .satisfies(account -> assertThat(account).containsEntry("archived", true));
    }

    /** One of Ana's categories, by its key. */
    private UUID category(String key) {
        return Objects.requireNonNull(anasCategories.get(key), key);
    }

    private MvcTestResult turnOn(UUID userId, UUID categoryId, UUID accountId) {
        return mvc.put()
                .uri(mirrorPath())
                .with(user(userId.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"categoryId\": \"%s\", \"accountId\": \"%s\"}".formatted(categoryId, accountId))
                .exchange();
    }

    private UUID groupAccountOf(MvcTestResult turnedOn) {
        return UUID.fromString((String) body(assertOk(turnedOn)).get("groupAccountId"));
    }

    private Map<String, Object> mirror(UUID userId) {
        return body(assertOk(
                mvc.get().uri(mirrorPath()).with(user(userId.toString())).exchange()));
    }

    private String mirrorPath() {
        return "/v1/groups/" + group + "/mirror";
    }

    /** Every delivered publication is deleted (ADR-0014), so an empty table means every listener ran. */
    private void awaitListeners() {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> jdbc.sql("SELECT count(*) FROM event_publication")
                                .query(Long.class)
                                .single()
                        == 0);
    }

    private List<Map<String, Object>> transactions(UUID userId) {
        return JsonPath.read(transactionsBody(userId), "$.items");
    }

    private String transactionsBody(UUID userId) {
        return text(assertOk(mvc.get()
                .uri("/v1/transactions?month=" + YearMonth.from(DAY))
                .with(user(userId.toString()))
                .exchange()));
    }

    private static Map<String, Object> item(List<Map<String, Object>> items, String description) {
        return items.stream()
                .filter(item -> description.equals(item.get("description")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No transaction " + description + " in " + items));
    }

    private List<Map<String, Object>> accounts(UUID userId) {
        return JsonPath.read(
                text(assertOk(mvc.get()
                        .uri("/v1/accounts")
                        .with(user(userId.toString()))
                        .exchange())),
                "$.items");
    }

    private long balanceOf(UUID userId, UUID accountId) {
        return accounts(userId).stream()
                .filter(account -> accountId.toString().equals(account.get("id")))
                .map(account -> ((Number) JsonPath.read(account, "$.balance.amountMinor")).longValue())
                .findFirst()
                .orElseThrow();
    }

    private long groupBalanceOf(UUID userId) {
        String balances = text(assertOk(mvc.get()
                .uri("/v1/groups/" + group + "/balances")
                .with(user(userId.toString()))
                .exchange()));
        List<Number> mine = JsonPath.read(balances, "$.items[?(@.userId == '" + userId + "')].balance.amountMinor");
        return mine.getFirst().longValue();
    }

    private Map<String, Integer> spendingByCategory(UUID userId) {
        String report = text(assertOk(mvc.get()
                .uri("/v1/reports/spending?from=%s&to=%s&groupBy=category"
                        .formatted(
                                YearMonth.from(DAY).atDay(1),
                                YearMonth.from(DAY).atEndOfMonth()))
                .with(user(userId.toString()))
                .exchange()));
        List<Map<String, Object>> categories = JsonPath.read(report, "$.categories");
        return categories.stream()
                .collect(java.util.stream.Collectors.toMap(
                        category -> (String) category.get("categoryId"),
                        category -> ((Number) JsonPath.read(category, "$.spending.amountMinor")).intValue()));
    }

    private Map<String, UUID> categoriesByKey(UUID userId) {
        String body = text(assertOk(
                mvc.get().uri("/v1/categories").with(user(userId.toString())).exchange()));
        List<Map<String, Object>> categories = JsonPath.read(body, "$.items[?(@.key)]");
        return categories.stream()
                .collect(java.util.stream.Collectors.toMap(
                        category -> (String) category.get("key"),
                        category -> UUID.fromString((String) category.get("id"))));
    }

    private UUID newAccount(UUID userId, String type, String name) {
        MvcTestResult created = mvc.post()
                .uri("/v1/accounts")
                .with(user(userId.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"%s\", \"type\": \"%s\", \"currency\": \"EUR\"}".formatted(name, type))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(JsonPath.read(text(created), "$.id"));
    }

    private UUID newGroup(UUID by) {
        MvcTestResult created = mvc.post()
                .uri("/v1/groups")
                .with(user(by.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Porto weekend\"}")
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(JsonPath.read(text(created), "$.id"));
    }

    private void join(UUID userId) {
        MvcTestResult link = mvc.post()
                .uri("/v1/groups/" + group + "/invites")
                .with(user(ana.toString()))
                .with(csrf())
                .exchange();
        assertThat(link).hasStatus(HttpStatus.CREATED);
        assertOk(mvc.post()
                .uri("/v1/invites/accept")
                .with(user(userId.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"%s\"}".formatted(JsonPath.<String>read(text(link), "$.token")))
                .exchange());
    }

    /** Paid by {@code by}, split equally between Ana and João. */
    private UUID recordExpense(UUID by, long amountMinor, String description, String category) {
        MvcTestResult recorded = mvc.post()
                .uri("/v1/groups/" + group + "/expenses")
                .with(user(by.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(expense(amountMinor, description, category, by))
                .exchange();
        assertThat(recorded).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(JsonPath.read(text(recorded), "$.id"));
    }

    /** Still paid by Ana; returns the id of the corrected version. */
    private UUID editExpense(UUID by, UUID expense, long amountMinor, String description, String category) {
        MvcTestResult edited = mvc.post()
                .uri("/v1/groups/" + group + "/expenses/" + expense + "/reverse")
                .with(user(by.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"replacement\": %s}".formatted(expense(amountMinor, description, category, ana)))
                .exchange();
        return UUID.fromString(JsonPath.read(text(assertOk(edited)), "$.currentId"));
    }

    private void recordSettlement(UUID by, long amountMinor, UUID from, UUID to) {
        assertThat(mvc.post()
                        .uri("/v1/groups/" + group + "/settlements")
                        .with(user(by.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": {"amountMinor": %d, "currency": "EUR"}, "occurredOn": "%s",
                                 "from": "%s", "to": "%s"}""".formatted(amountMinor, DAY, from, to))
                        .exchange())
                .hasStatus(HttpStatus.CREATED);
    }

    private String expense(long amountMinor, String description, String category, UUID paidBy) {
        return """
                {"amount": {"amountMinor": %d, "currency": "EUR"}, "occurredOn": "%s", "description": "%s",
                 "category": "%s", "paidBy": "%s",
                 "split": {"method": "EQUALLY", "members": [{"userId": "%s"}, {"userId": "%s"}]}}""".formatted(amountMinor, DAY, description, category, paidBy, ana, joao);
    }

    private UUID newUser(String name) {
        return jdbc.sql("INSERT INTO users (email, name) VALUES (:email, :name) RETURNING id")
                .param("email", "mirror-" + UUID.randomUUID() + "@example.com")
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    private static Map<String, Object> eur(long minor) {
        return Map.of("amountMinor", (int) minor, "currency", "EUR");
    }

    private static MvcTestResult assertOk(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return result;
    }

    private static Map<String, Object> body(MvcTestResult result) {
        return JsonPath.read(text(result), "$");
    }

    private static String text(MvcTestResult result) {
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
