package io.github.muzachio.moneytrail.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.jayway.jsonpath.JsonPath;
import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.identity.UserSignedIn;
import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GET /v1/reports/spending: spending by category and by month, read from the user's own ledger. The
 * entries are recorded through the transactions API, as the app records them; every amount is
 * invented.
 */
@AutoConfigureMockMvc
class SpendingReportApiTests extends PostgresIntegrationTest {

    private static final String REPORT = "/v1/reports/spending";
    private static final String OCTOBER = "from=2026-10-01&to=2026-10-31";

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

    private UUID ana;
    private UUID checking;
    private UUID card;
    private UUID savings;
    private UUID food;
    private UUID transport;
    private UUID shopping;
    private UUID leisure;
    private UUID health;
    private UUID salary;
    private UUID refunds;

    @BeforeEach
    void signInAnaWithThreeAccounts() {
        ana = signedInUser();
        checking = createAccount(ana, "CHECKING", "Everyday checking", 218437);
        card = createAccount(ana, "CREDIT_CARD", "Credit card", -31240);
        savings = createAccount(ana, "SAVINGS", "Emergency fund", 0);
        food = categoryOf(ana, "food");
        transport = categoryOf(ana, "transport");
        shopping = categoryOf(ana, "shopping");
        leisure = categoryOf(ana, "leisure");
        health = categoryOf(ana, "health");
        salary = categoryOf(ana, "salary");
        refunds = categoryOf(ana, "refunds");
    }

    @Test
    void byCategoryAddsUpEachExpenseCategoryLargestFirstWithThePeriodsTotals() {
        record(ana, expense(food, checking, 980, "Lunch", "2026-10-20"));
        record(ana, expense(food, card, 3872, "Groceries", "2026-10-24"));
        record(ana, expense(health, checking, 1290, "Pharmacy", "2026-10-21"));
        record(ana, expense(shopping, card, 1290, "Phone case", "2026-10-22"));
        record(ana, income(salary, checking, 187499, "October salary", "2026-10-01"));
        record(ana, income(refunds, checking, 2499, "Headphones refund", "2026-10-20"));
        record(ana, expense(food, card, 9999, "Last month", "2026-09-30"));
        record(ana, income(salary, checking, 9999, "Next month", "2026-11-01"));

        var body = assertThat(report(ana, OCTOBER + "&groupBy=category"))
                .hasStatusOk()
                .bodyJson();

        body.extractingPath("$.income").isEqualTo(eur(187499 + 2499));
        body.extractingPath("$.spending").isEqualTo(eur(980 + 3872 + 1290 + 1290));
        // Shopping and health tie; shopping is a default category opened before health.
        body.extractingPath("$.categories")
                .isEqualTo(List.of(
                        category(food, "food", null, 980 + 3872),
                        category(shopping, "shopping", null, 1290),
                        category(health, "health", null, 1290)));
        body.doesNotHavePath("$.months");
    }

    @Test
    void byMonthHasEveryMonthOfThePeriodOldestFirstWithZerosForMonthsWithoutMovements() {
        record(ana, expense(food, card, 5000, "Groceries", "2026-06-12"));
        record(ana, income(salary, checking, 150000, "August salary", "2026-08-01"));
        record(ana, expense(health, checking, 1200, "Dentist", "2026-08-19"));
        record(ana, expense(food, card, 800, "Coffee beans", "2026-11-30"));
        record(ana, expense(food, card, 9999, "Before the period", "2026-05-31"));
        record(ana, expense(food, card, 9999, "After the period", "2026-12-01"));

        var body = assertThat(report(ana, "from=2026-06-01&to=2026-11-30&groupBy=month"))
                .hasStatusOk()
                .bodyJson();

        body.extractingPath("$.months")
                .isEqualTo(List.of(
                        month("2026-06", 0, 5000),
                        month("2026-07", 0, 0),
                        month("2026-08", 150000, 1200),
                        month("2026-09", 0, 0),
                        month("2026-10", 0, 0),
                        month("2026-11", 0, 800)));
        body.extractingPath("$.income").isEqualTo(eur(150000));
        body.extractingPath("$.spending").isEqualTo(eur(5000 + 1200 + 800));
        body.doesNotHavePath("$.categories");
    }

    @Test
    void countsBothDaysOfThePeriodAndNothingOutsideThem() {
        record(ana, expense(food, card, 100, "The day before", "2026-06-14"));
        record(ana, expense(food, card, 200, "The first day", "2026-06-15"));
        record(ana, expense(food, card, 400, "The last day", "2026-07-10"));
        record(ana, expense(food, card, 800, "The day after", "2026-07-11"));

        assertThat(report(ana, "from=2026-06-15&to=2026-07-10&groupBy=month"))
                .bodyJson()
                .extractingPath("$.months")
                .isEqualTo(List.of(month("2026-06", 0, 200), month("2026-07", 0, 400)));
        assertThat(report(ana, "from=2026-06-15&to=2026-07-10&groupBy=category"))
                .bodyJson()
                .extractingPath("$.categories")
                .isEqualTo(List.of(category(food, "food", null, 200 + 400)));
        assertThat(report(ana, "from=2026-07-10&to=2026-07-10&groupBy=month"))
                .bodyJson()
                .extractingPath("$.months")
                .isEqualTo(List.of(month("2026-07", 0, 400)));
    }

    /** The accounts' opening balances were posted today, when signInAnaWithThreeAccounts created them. */
    @Test
    void transfersAndOpeningBalancesNeverCount() {
        record(ana, transfer(checking, savings, 20000, "Emergency fund top-up", "2026-10-23"));
        record(ana, transfer(checking, card, 31240, "Card payment", "2026-10-25"));
        record(ana, expense(food, card, 980, "Lunch", "2026-10-20"));
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Lisbon"));
        String thisMonth = "from=" + today.withDayOfMonth(1) + "&to=" + today;

        var october = assertThat(report(ana, OCTOBER + "&groupBy=category"))
                .hasStatusOk()
                .bodyJson();
        october.extractingPath("$.income").isEqualTo(eur(0));
        october.extractingPath("$.spending").isEqualTo(eur(980));
        october.extractingPath("$.categories").isEqualTo(List.of(category(food, "food", null, 980)));
        assertThat(report(ana, thisMonth + "&groupBy=category")).bodyJson().isStrictlyEqualTo("""
                        {"income": {"amountMinor": 0, "currency": "EUR"},
                         "spending": {"amountMinor": 0, "currency": "EUR"},
                         "categories": []}""");
        assertThat(report(ana, thisMonth + "&groupBy=month"))
                .bodyJson()
                .extractingPath("$.months")
                .isEqualTo(List.of(month(YearMonth.from(today).toString(), 0, 0)));
    }

    @Test
    void aReversedTransactionStopsCountingAndAnEditedOneCountsAsCorrected() {
        UUID dinner = record(ana, expense(food, card, 2570, "Dinner out", "2026-10-22"));
        edit(dinner, expense(food, card, 2750, "Dinner out", "2026-10-22"));
        UUID cinema = record(ana, expense(leisure, card, 1500, "Cinema", "2026-10-24"));
        reverse(cinema);
        UUID pharmacy = record(ana, expense(food, checking, 1290, "Pharmacy", "2026-10-21"));
        edit(pharmacy, expense(health, checking, 1290, "Pharmacy", "2026-10-21"));
        UUID taxi = record(ana, expense(transport, card, 1800, "Taxi", "2026-10-30"));
        edit(taxi, expense(transport, card, 1800, "Taxi", "2026-11-02"));
        UUID pay = record(ana, income(salary, checking, 187499, "October salary", "2026-10-01"));
        edit(pay, income(salary, checking, 187500, "October salary", "2026-10-01"));
        UUID refund = record(ana, income(refunds, checking, 2499, "Headphones refund", "2026-10-20"));
        reverse(refund);

        var byCategory = assertThat(report(ana, OCTOBER + "&groupBy=category"))
                .hasStatusOk()
                .bodyJson();
        byCategory.extractingPath("$.income").isEqualTo(eur(187500));
        byCategory.extractingPath("$.spending").isEqualTo(eur(2750 + 1290));
        byCategory
                .extractingPath("$.categories")
                .as("the cinema reversed, the pharmacy moved to health, the taxi to November")
                .isEqualTo(List.of(category(food, "food", null, 2750), category(health, "health", null, 1290)));

        var byMonth = assertThat(report(ana, "from=2026-10-01&to=2026-11-30&groupBy=month"))
                .hasStatusOk()
                .bodyJson();
        byMonth.extractingPath("$.months")
                .isEqualTo(List.of(month("2026-10", 187500, 2750 + 1290), month("2026-11", 0, 1800)));
        byMonth.extractingPath("$.spending").isEqualTo(eur(2750 + 1290 + 1800));
    }

    @Test
    void namesTheCategoriesAsTheCategoriesEndpointDoes() {
        UUID pets = createCategory(ana, "Pets");
        record(ana, expense(food, card, 4000, "Groceries", "2026-10-02"));
        record(ana, expense(health, card, 3000, "Dentist", "2026-10-03"));
        record(ana, expense(pets, card, 2000, "Cat food", "2026-10-04"));
        record(ana, expense(shopping, card, 1000, "Umbrella", "2026-10-05"));
        updateCategory(ana, health, "{\"name\": \"Doctors\"}");
        updateCategory(ana, shopping, "{\"archived\": true}");

        MvcTestResult report = report(ana, OCTOBER + "&groupBy=category");

        assertThat(report)
                .bodyJson()
                .extractingPath("$.categories")
                .as("a renamed default keeps its key, and an archived category still counts")
                .isEqualTo(List.of(
                        category(food, "food", null, 4000),
                        category(health, "health", "Doctors", 3000),
                        category(pets, null, "Pets", 2000),
                        category(shopping, "shopping", null, 1000)));
        List<Map<String, Object>> listed = JsonPath.read(bodyOf(categories(ana)), "$.items");
        Map<Object, Map<String, Object>> byId = new LinkedHashMap<>();
        listed.forEach(category -> byId.put(category.get("id"), category));
        List<Map<String, Object>> reported = JsonPath.read(bodyOf(report), "$.categories");
        assertThat(reported).allSatisfy(category -> {
            Map<String, Object> same = Objects.requireNonNull(byId.get(category.get("categoryId")));
            assertThat(category.get("key")).isEqualTo(same.get("key"));
            assertThat(category.get("name")).isEqualTo(same.get("name"));
        });
    }

    @Test
    void readsOnlyTheUsersOwnLedger() {
        record(ana, expense(food, card, 980, "Lunch", "2026-10-20"));
        UUID rui = signedInUser();
        UUID ruisChecking = createAccount(rui, "CHECKING", "Main account", 0);
        UUID ruisHealth = categoryOf(rui, "health");
        record(rui, expense(ruisHealth, ruisChecking, 5000, "Physio", "2026-10-21"));
        record(rui, income(categoryOf(rui, "salary"), ruisChecking, 99000, "October salary", "2026-10-01"));

        var anas = assertThat(report(ana, OCTOBER + "&groupBy=category")).bodyJson();
        anas.extractingPath("$.income").isEqualTo(eur(0));
        anas.extractingPath("$.categories").isEqualTo(List.of(category(food, "food", null, 980)));
        var ruis = assertThat(report(rui, OCTOBER + "&groupBy=category")).bodyJson();
        ruis.extractingPath("$.income").isEqualTo(eur(99000));
        ruis.extractingPath("$.categories").isEqualTo(List.of(category(ruisHealth, "health", null, 5000)));
        assertThat(report(rui, OCTOBER + "&groupBy=month"))
                .bodyJson()
                .extractingPath("$.months")
                .isEqualTo(List.of(month("2026-10", 99000, 5000)));
    }

    /** A session from before #42 may belong to a user who has no ledger yet: a report reads, never opens one. */
    @Test
    void aUserWithoutALedgerGetsZerosAndStillHasNoLedger() {
        UUID newcomer = userWithoutALedger();

        assertThat(report(newcomer, OCTOBER + "&groupBy=category"))
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo("""
                        {"income": {"amountMinor": 0, "currency": "EUR"},
                         "spending": {"amountMinor": 0, "currency": "EUR"},
                         "categories": []}""");
        assertThat(report(newcomer, "from=2026-09-15&to=2026-10-15&groupBy=month"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.months")
                .isEqualTo(List.of(month("2026-09", 0, 0), month("2026-10", 0, 0)));
        assertThat(ledger.findLedger(LedgerOwner.user(newcomer))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "from=2026-10-01&to=2026-10-31",
                "to=2026-10-31&groupBy=month",
                "from=2026-10-01&groupBy=month",
                "from=2026-10-1&to=2026-10-31&groupBy=month",
                "from=2026-02-30&to=2026-03-31&groupBy=month",
                "from=October&to=2026-10-31&groupBy=month",
                "from=2026-10-01&to=2026-10-31&groupBy=week",
                "from=2026-10-01&to=2026-10-31&groupBy="
            })
    void refusesAMissingOrMalformedParameter(String query) {
        assertProblem(report(ana, query), "bad_request");
    }

    @Test
    void refusesAPeriodThatEndsBeforeItStartsOrCoversMoreThan24Months() {
        assertProblem(report(ana, "from=2026-10-02&to=2026-10-01&groupBy=category"), "invalid_period");
        assertProblem(report(ana, "from=2024-11-30&to=2026-11-01&groupBy=month"), "invalid_period");
        assertThat(report(ana, "from=2024-12-31&to=2026-11-01&groupBy=month"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.months")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .hasSize(24);
    }

    @Test
    void withoutASessionTheReportAnswers401() {
        assertThat(mvc.get().uri(REPORT + "?" + OCTOBER + "&groupBy=category")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    /** A user who has signed in: the sign-in transaction published UserSignedIn. */
    private UUID signedInUser() {
        UUID userId = userWithoutALedger();
        transactions.executeWithoutResult(status -> events.publishEvent(new UserSignedIn(userId)));
        return userId;
    }

    private UUID userWithoutALedger() {
        return jdbc.sql("INSERT INTO users (email, name) VALUES (:email, 'Reports Test') RETURNING id")
                .param("email", "reports-" + UUID.randomUUID() + "@example.com")
                .query(UUID.class)
                .single();
    }

    private UUID createAccount(UUID userId, String type, String name, long openingBalance) {
        return idOf(created(mvc.post()
                .uri("/v1/accounts")
                .with(user(userId.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "type": "%s", "currency": "EUR",
                         "openingBalance": {"amountMinor": %d, "currency": "EUR"}}""".formatted(name, type, openingBalance))
                .exchange()));
    }

    private UUID createCategory(UUID userId, String name) {
        return idOf(created(mvc.post()
                .uri("/v1/categories")
                .with(user(userId.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\": \"EXPENSE\", \"name\": \"%s\"}".formatted(name))
                .exchange()));
    }

    private void updateCategory(UUID userId, UUID categoryId, String body) {
        assertThat(mvc.patch()
                        .uri("/v1/categories/" + categoryId)
                        .with(user(userId.toString()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .hasStatusOk();
    }

    private MvcTestResult categories(UUID userId) {
        MvcTestResult result =
                mvc.get().uri("/v1/categories").with(user(userId.toString())).exchange();
        assertThat(result).hasStatusOk();
        return result;
    }

    private UUID record(UUID userId, String body) {
        return idOf(created(mvc.post()
                .uri("/v1/transactions")
                .with(user(userId.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange()));
    }

    private void edit(UUID transactionId, String replacement) {
        reverse(transactionId, "{\"replacement\": " + replacement + "}");
    }

    private void reverse(UUID transactionId) {
        reverse(transactionId, "{}");
    }

    private void reverse(UUID transactionId, String body) {
        assertThat(mvc.post()
                        .uri("/v1/transactions/" + transactionId + "/reverse")
                        .with(user(ana.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .hasStatusOk();
    }

    private MvcTestResult report(UUID userId, String query) {
        var request = mvc.get().uri(REPORT).with(user(userId.toString()));
        for (String pair : query.split("&", -1)) {
            if (!pair.isEmpty()) {
                int equals = pair.indexOf('=');
                request.param(pair.substring(0, equals), pair.substring(equals + 1));
            }
        }
        return request.exchange();
    }

    private static String expense(UUID categoryId, UUID accountId, long amountMinor, String description, String day) {
        return movement("EXPENSE", amountMinor, description, day)
                + ", \"categoryId\": \"%s\", \"accountId\": \"%s\"}".formatted(categoryId, accountId);
    }

    private static String income(UUID categoryId, UUID accountId, long amountMinor, String description, String day) {
        return movement("INCOME", amountMinor, description, day)
                + ", \"categoryId\": \"%s\", \"accountId\": \"%s\"}".formatted(categoryId, accountId);
    }

    private static String transfer(
            UUID fromAccountId, UUID toAccountId, long amountMinor, String description, String day) {
        return movement("TRANSFER", amountMinor, description, day)
                + ", \"fromAccountId\": \"%s\", \"toAccountId\": \"%s\"}".formatted(fromAccountId, toAccountId);
    }

    private static String movement(String type, long amountMinor, String description, String day) {
        return """
                {"type": "%s", "amount": {"amountMinor": %d, "currency": "EUR"}, "occurredOn": "%s",
                 "description": "%s\"""".formatted(type, amountMinor, day, description);
    }

    private UUID categoryOf(UUID userId, String key) {
        UUID ledgerId =
                ledger.findLedger(LedgerOwner.user(userId)).orElseThrow().id();
        return ledger.accounts(ledgerId).stream()
                .filter(account -> key.equals(account.subtype()))
                .map(Account::id)
                .findFirst()
                .orElseThrow();
    }

    /** What the report sends for a category: the key and the name only when the category has them. */
    private static Map<String, Object> category(UUID id, @Nullable String key, @Nullable String name, long spending) {
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("categoryId", id.toString());
        if (key != null) {
            category.put("key", key);
        }
        if (name != null) {
            category.put("name", name);
        }
        category.put("spending", eur(spending));
        return category;
    }

    private static Map<String, Object> month(String month, long income, long spending) {
        return Map.of("month", month, "income", eur(income), "spending", eur(spending));
    }

    private static Map<String, Object> eur(long minor) {
        // JSON numbers read back as Integer when they fit, and Map.equals compares the types too.
        return Map.of("amountMinor", Math.toIntExact(minor), "currency", "EUR");
    }

    private static MvcTestResult created(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return result;
    }

    private static UUID idOf(MvcTestResult result) {
        return UUID.fromString(JsonPath.read(bodyOf(result), "$.id"));
    }

    private static String bodyOf(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static void assertProblem(MvcTestResult result, String code) {
        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo(code);
    }
}
