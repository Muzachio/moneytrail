package io.github.muzachio.moneytrail.personal.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.jayway.jsonpath.JsonPath;
import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.identity.UserSignedIn;
import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/** GET /v1/transactions: keyset pages, the filters and the month's totals. */
@AutoConfigureMockMvc
class TransactionListApiTests extends PostgresIntegrationTest {

    private static final String TRANSACTIONS = "/v1/transactions";
    private static final String OCTOBER = "2026-10";

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
        health = categoryOf(ana, "health");
        salary = categoryOf(ana, "salary");
        refunds = categoryOf(ana, "refunds");
    }

    @Test
    void pagesThroughTheMonthNewestFirstWithoutRepeatingOrSkippingAny() {
        List<UUID> newestFirst = new ArrayList<>();
        newestFirst.addFirst(record(expense(food, checking, 980, "Lunch", "2026-10-20")));
        newestFirst.addFirst(record(income(refunds, checking, 2499, "Headphones refund", "2026-10-20")));
        newestFirst.addFirst(record(expense(health, checking, 1290, "Pharmacy", "2026-10-21")));
        newestFirst.addFirst(record(transfer(checking, savings, 20000, "Emergency fund top-up", "2026-10-23")));
        newestFirst.addFirst(record(expense(food, card, 3872, "Groceries", "2026-10-24")));
        newestFirst.addFirst(record(expense(food, card, 1840, "Brunch", "2026-10-25")));
        newestFirst.addFirst(record(expense(food, card, 1500, "Cinema snacks", "2026-10-25")));
        UUID september = record(expense(food, card, 700, "Coffee", "2026-09-30"));
        UUID november = record(expense(food, card, 700, "Coffee", "2026-11-01"));

        MvcTestResult first = list(ana, "month=2026-10&limit=3");
        MvcTestResult second = list(ana, "month=2026-10&limit=3&cursor=" + read(first, "$.nextCursor"));
        MvcTestResult third = list(ana, "month=2026-10&limit=3&cursor=" + read(second, "$.nextCursor"));

        assertThat(idsOf(first)).containsExactlyElementsOf(newestFirst.subList(0, 3));
        assertThat(idsOf(second)).containsExactlyElementsOf(newestFirst.subList(3, 6));
        assertThat(idsOf(third)).containsExactlyElementsOf(newestFirst.subList(6, 7));
        assertThat(third).bodyJson().doesNotHavePath("$.nextCursor");
        for (MvcTestResult page : List.of(first, second, third)) {
            assertThat(idsOf(page)).as("the months around it").doesNotContain(september, november);
            assertThat(page).bodyJson().extractingPath("$.month").isEqualTo(OCTOBER);
            assertThat(page).bodyJson().extractingPath("$.summary.count").isEqualTo(7);
        }
    }

    /**
     * With an offset, a transaction added to the first page while the second was loading would push
     * the first page's last one onto the second page, and show it twice. The cursor names that last
     * one instead, so the next page starts right after it whatever was added before it.
     */
    @Test
    void aTransactionRecordedWhilePagingNeitherRepeatsNorSkipsAnother() {
        UUID oldest = record(expense(food, card, 100, "Older", "2026-10-01"));
        UUID middle = record(expense(food, card, 200, "Middle", "2026-10-10"));
        UUID newest = record(expense(food, card, 300, "Newer", "2026-10-20"));
        MvcTestResult first = list(ana, "month=2026-10&limit=2");

        UUID addedOnTop = record(expense(food, card, 400, "Added on top", "2026-10-25"));
        UUID addedBelow = record(expense(food, card, 500, "Added below", "2026-10-05"));
        MvcTestResult second = list(ana, "month=2026-10&limit=2&cursor=" + read(first, "$.nextCursor"));

        assertThat(idsOf(first)).containsExactly(newest, middle);
        assertThat(idsOf(second)).containsExactly(addedBelow, oldest);
        assertThat(idsOf(second)).doesNotContain(addedOnTop, middle);
    }

    @Test
    void listsTheCurrentMonthWhenNoneIsGiven() {
        LocalDate today = LocalDate.now(PersonalLedgers.TIME_ZONE);
        UUID thisMonth = record(expense(food, card, 980, "Lunch", today.toString()));
        record(expense(
                food, card, 980, "Lunch", today.withDayOfMonth(1).minusDays(1).toString()));

        var body = assertThat(list(ana, "")).hasStatusOk().bodyJson();

        body.extractingPath("$.month").isEqualTo(YearMonth.from(today).toString());
        body.extractingPath("$.items[*].id")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(thisMonth.toString());
    }

    @Test
    void filtersByType() {
        UUID lunch = record(expense(food, checking, 980, "Lunch", "2026-10-20"));
        UUID refund = record(income(refunds, checking, 2499, "Headphones refund", "2026-10-21"));
        UUID topUp = record(transfer(checking, savings, 20000, "Emergency fund top-up", "2026-10-22"));

        assertThat(idsOf(list(ana, "month=2026-10&type=EXPENSE"))).containsExactly(lunch);
        assertThat(idsOf(list(ana, "month=2026-10&type=INCOME"))).containsExactly(refund);
        assertThat(idsOf(list(ana, "month=2026-10&type=TRANSFER"))).containsExactly(topUp);
        assertThat(idsOf(list(ana, "month=2026-10"))).containsExactly(topUp, refund, lunch);
    }

    @Test
    void filtersByAccountWhichIncludesTransfersFromAndToIt() {
        UUID lunch = record(expense(food, checking, 980, "Lunch", "2026-10-20"));
        UUID brunch = record(expense(food, card, 1840, "Brunch", "2026-10-21"));
        UUID cardPayment = record(transfer(checking, card, 31240, "Card payment", "2026-10-22"));
        UUID topUp = record(transfer(checking, savings, 20000, "Emergency fund top-up", "2026-10-23"));

        assertThat(idsOf(list(ana, "month=2026-10&accountId=" + card))).containsExactly(cardPayment, brunch);
        assertThat(idsOf(list(ana, "month=2026-10&accountId=" + checking))).containsExactly(topUp, cardPayment, lunch);
        assertThat(idsOf(list(ana, "month=2026-10&accountId=" + savings))).containsExactly(topUp);
    }

    @Test
    void filtersByCategory() {
        UUID lunch = record(expense(food, checking, 980, "Lunch", "2026-10-20"));
        record(expense(health, checking, 1290, "Pharmacy", "2026-10-21"));
        UUID groceries = record(expense(food, card, 3872, "Groceries", "2026-10-24"));

        assertThat(idsOf(list(ana, "month=2026-10&categoryId=" + food))).containsExactly(groceries, lunch);
        assertThat(idsOf(list(ana, "month=2026-10&categoryId=" + salary))).isEmpty();
    }

    @Test
    void filtersByTextInTheDescriptionIgnoringCaseAndTakingWildcardsLiterally() {
        UUID pharmacy = record(expense(health, checking, 1290, "Farmácia SAÚDE", "2026-10-21"));
        UUID percent = record(expense(food, checking, 500, "50% off lunch", "2026-10-22"));
        record(expense(food, checking, 980, "Lunch", "2026-10-23"));

        assertThat(idsOf(list(ana, "month=2026-10&q=saúde"))).containsExactly(pharmacy);
        assertThat(idsOf(list(ana, "month=2026-10&q=FARMÁ"))).containsExactly(pharmacy);
        assertThat(idsOf(list(ana, "month=2026-10&q=%25"))).containsExactly(percent);
        assertThat(idsOf(list(ana, "month=2026-10&q=_"))).isEmpty();
        assertThat(idsOf(list(ana, "month=2026-10&q=%20%20"))).hasSize(3);
    }

    @Test
    void theFiltersCombine() {
        UUID brunch = record(expense(food, card, 1840, "Brunch with Ana", "2026-10-21"));
        record(expense(food, checking, 1840, "Brunch", "2026-10-21"));
        record(expense(health, card, 1290, "Pharmacy", "2026-10-21"));
        record(expense(food, card, 980, "Lunch", "2026-10-22"));

        assertThat(idsOf(list(
                        ana, "month=2026-10&type=EXPENSE&accountId=" + card + "&categoryId=" + food + "&q=brunch")))
                .containsExactly(brunch);
    }

    @Test
    void showsOnlyTheCurrentVersionOfEachTransactionAndFlagsEditedOnes() {
        UUID dinner = record(expense(food, card, 2570, "Dinner out", "2026-10-22"));
        UUID corrected = edit(dinner, expense(food, card, 2750, "Dinner out", "2026-10-22"));
        UUID cinema = record(expense(food, card, 1500, "Cinema", "2026-10-24"));
        reverse(cinema);
        UUID lunch = record(expense(food, checking, 980, "Lunch", "2026-10-20"));

        var body = assertThat(list(ana, "month=2026-10")).hasStatusOk().bodyJson();

        body.extractingPath("$.items[*].id")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .as("no opening balance, no reversal, no old or reversed version")
                .containsExactly(corrected.toString(), lunch.toString());
        body.extractingPath("$.items[*].edited")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(true, false);
        body.extractingPath("$.items[*].reversed")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsOnly(false);
        body.extractingPath("$.items[0].amount.amountMinor").isEqualTo(2750);
        body.extractingPath("$.summary.count").isEqualTo(2);
        body.extractingPath("$.summary.spending.amountMinor").isEqualTo(2750 + 980);
    }

    @Test
    void theSummaryCoversTheWholeMonthAndLeavesTransfersOutOfTheSums() {
        record(income(salary, checking, 187499, "October salary", "2026-10-01"));
        record(income(refunds, checking, 2499, "Headphones refund", "2026-10-20"));
        record(expense(food, card, 3872, "Groceries", "2026-10-24"));
        record(expense(health, checking, 1290, "Pharmacy", "2026-10-21"));
        record(transfer(checking, savings, 20000, "Emergency fund top-up", "2026-10-23"));
        record(transfer(checking, card, 31240, "Card payment", "2026-10-25"));
        record(expense(food, card, 9999, "Last month", "2026-09-30"));

        var all = assertThat(list(ana, "month=2026-10&limit=1")).hasStatusOk().bodyJson();
        all.extractingPath("$.items")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .hasSize(1);
        all.extractingPath("$.summary")
                .isEqualTo(Map.of(
                        "count", 6,
                        "income", eur(187499 + 2499),
                        "spending", eur(3872 + 1290),
                        "net", eur(187499 + 2499 - 3872 - 1290)));

        assertThat(list(ana, "month=2026-10&accountId=" + card))
                .bodyJson()
                .extractingPath("$.summary")
                .isEqualTo(Map.of("count", 2, "income", eur(0), "spending", eur(3872), "net", eur(-3872)));
        assertThat(list(ana, "month=2026-10&type=TRANSFER"))
                .bodyJson()
                .extractingPath("$.summary")
                .isEqualTo(Map.of("count", 2, "income", eur(0), "spending", eur(0), "net", eur(0)));
        assertThat(list(ana, "month=2026-10&categoryId=" + health + "&q=pharm"))
                .bodyJson()
                .extractingPath("$.summary")
                .isEqualTo(Map.of("count", 1, "income", eur(0), "spending", eur(1290), "net", eur(-1290)));
    }

    @Test
    void anEmptyMonthHasNoItemsNoCursorAndZeroTotals() {
        var body = assertThat(list(ana, "month=2026-03")).hasStatusOk().bodyJson();

        body.extractingPath("$.items")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .isEmpty();
        body.doesNotHavePath("$.nextCursor");
        body.extractingPath("$.summary")
                .isEqualTo(Map.of("count", 0, "income", eur(0), "spending", eur(0), "net", eur(0)));
    }

    @Test
    void anotherUsersTransactionsAreNotListedAndTheirAccountsAndCategoriesAreRefused() {
        record(expense(food, card, 1840, "Brunch", "2026-10-21"));
        UUID rui = signedInUser();

        assertThat(list(rui, "month=2026-10"))
                .bodyJson()
                .extractingPath("$.items")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .isEmpty();
        assertRefused(list(rui, "month=2026-10&accountId=" + card), "account_not_found");
        assertRefused(list(rui, "month=2026-10&categoryId=" + food), "category_not_found");
        assertRefused(list(ana, "month=2026-10&accountId=" + food), "account_not_found");
        assertRefused(list(ana, "month=2026-10&categoryId=" + card), "category_not_found");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "month=2026-13",
                "month=2026-1",
                "month=October",
                "type=REFUND",
                "accountId=checking",
                "limit=0",
                "limit=101",
                "limit=ten",
                "cursor=not-a-cursor",
                "cursor=MjAyNi0xMC0yMg"
            })
    void refusesAnInvalidQuery(String query) {
        assertThat(list(ana, query))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("bad_request");
    }

    @Test
    void withoutASessionTheListAnswers401() {
        assertThat(mvc.get().uri(TRANSACTIONS)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    /** A user who has signed in: the sign-in transaction published UserSignedIn. */
    private UUID signedInUser() {
        UUID userId = jdbc.sql("INSERT INTO users (email, name) VALUES (:email, 'Transactions Test') RETURNING id")
                .param("email", "transactions-" + UUID.randomUUID() + "@example.com")
                .query(UUID.class)
                .single();
        transactions.executeWithoutResult(status -> events.publishEvent(new UserSignedIn(userId)));
        return userId;
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

    private UUID record(String body) {
        return idOf(created(mvc.post()
                .uri(TRANSACTIONS)
                .with(user(ana.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange()));
    }

    private UUID edit(UUID transactionId, String replacement) {
        MvcTestResult edited = reverse(transactionId, "{\"replacement\": " + replacement + "}");
        return UUID.fromString(read(edited, "$.transaction.id"));
    }

    private void reverse(UUID transactionId) {
        reverse(transactionId, null);
    }

    private MvcTestResult reverse(UUID transactionId, @Nullable String body) {
        var request = mvc.post()
                .uri(TRANSACTIONS + "/" + transactionId + "/reverse")
                .with(user(ana.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcTestResult result = request.exchange();
        assertThat(result).hasStatusOk();
        return result;
    }

    /**
     * The query is written as in a URL, but sent as parameters: a URI template would encode the
     * {@code %} of {@code q=%25} a second time.
     */
    private MvcTestResult list(UUID userId, String query) {
        var request = mvc.get().uri(TRANSACTIONS).with(user(userId.toString()));
        for (String pair : query.split("&")) {
            if (!pair.isEmpty()) {
                int equals = pair.indexOf('=');
                request.param(
                        pair.substring(0, equals),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
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

    private UUID ledgerOf(UUID userId) {
        return ledger.findLedger(LedgerOwner.user(userId)).orElseThrow().id();
    }

    private UUID categoryOf(UUID userId, String key) {
        return ledger.accounts(ledgerOf(userId)).stream()
                .filter(account -> key.equals(account.subtype()))
                .map(Account::id)
                .findFirst()
                .orElseThrow();
    }

    private static MvcTestResult created(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return result;
    }

    private static List<UUID> idsOf(MvcTestResult page) {
        assertThat(page).hasStatusOk();
        List<String> ids = JsonPath.read(bodyOf(page), "$.items[*].id");
        return ids.stream().map(UUID::fromString).toList();
    }

    private static UUID idOf(MvcTestResult result) {
        return UUID.fromString(read(result, "$.id"));
    }

    private static String read(MvcTestResult result, String path) {
        return JsonPath.read(bodyOf(result), path);
    }

    private static String bodyOf(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> eur(long minor) {
        // JSON numbers read back as Integer when they fit, and Map.equals compares the types too.
        return Map.of("amountMinor", Math.toIntExact(minor), "currency", "EUR");
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static void assertRefused(MvcTestResult result, String code) {
        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo(code);
    }
}
