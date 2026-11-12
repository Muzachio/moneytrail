package io.github.muzachio.moneytrail.personal.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.jayway.jsonpath.JsonPath;
import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.identity.UserSignedIn;
import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

/** GET and POST /v1/accounts and PATCH /v1/accounts/{id} as in api/openapi.yaml. */
@AutoConfigureMockMvc
class AccountsApiTests extends PostgresIntegrationTest {

    private static final String ACCOUNTS = "/v1/accounts";

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

    @BeforeEach
    void signInAna() {
        ana = signedInUser();
    }

    @Test
    void aNewUserHasNoAccountsBecauseCategoriesAndEquityAreNotAccounts() {
        assertThat(get(ana))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .extractingPath("$.items")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "CHECKING, ASSET, checking",
        "SAVINGS, ASSET, savings",
        "CASH, ASSET, cash",
        "CREDIT_CARD, LIABILITY, credit-card"
    })
    void createsAnAccountOfEachTypeAsAnAssetOrALiability(String type, AccountKind kind, String key) {
        MvcTestResult created = post(ana, newKey(), """
                {"name": "  Everyday  ", "type": "%s", "currency": "EUR"}""".formatted(type));

        var body = assertThat(created)
                .hasStatus(HttpStatus.CREATED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson();
        body.extractingPath("$.name").isEqualTo("Everyday");
        body.extractingPath("$.type").isEqualTo(type);
        body.extractingPath("$.currency").isEqualTo("EUR");
        body.extractingPath("$.balance").isEqualTo(Map.of("amountMinor", 0, "currency", "EUR"));
        body.extractingPath("$.changeThisMonth").isEqualTo(Map.of("amountMinor", 0, "currency", "EUR"));
        body.extractingPath("$.archived").isEqualTo(false);
        body.doesNotHavePath("$.archivedAt");

        UUID id = idOf(created);
        assertThat(ledger.findAccount(id)).hasValueSatisfying(account -> {
            assertThat(account.kind()).isEqualTo(kind);
            assertThat(account.subtype()).isEqualTo(key);
            assertThat(account.currency().getCurrencyCode()).isEqualTo("EUR");
            assertThat(account.ledgerId()).isEqualTo(ledgerOf(ana));
        });
        assertThat(entriesOf(ana)).as("no opening balance, no entry").isZero();
        assertThat(get(ana))
                .bodyJson()
                .extractingPath("$.items[*].id")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(id.toString());
    }

    @Test
    void anOpeningBalanceIsPostedAgainstOpeningBalancesInTheSameTransaction() {
        MvcTestResult created = post(ana, newKey(), """
                {"name": "Everyday checking", "type": "CHECKING", "currency": "EUR",
                 "openingBalance": {"amountMinor": 218437, "currency": "EUR"}}""");

        var body = assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson();
        body.extractingPath("$.balance.amountMinor").isEqualTo(218437);
        body.extractingPath("$.changeThisMonth.amountMinor")
                .as("money the user already had, not money that moved this month")
                .isEqualTo(0);
        UUID checking = idOf(created);
        UUID openingBalances = openingBalancesOf(ana);
        assertThat(postingsOf(ana))
                .containsExactly(
                        new Line(today(), UserAccounts.OPENING_BALANCE, checking, 218437),
                        new Line(today(), UserAccounts.OPENING_BALANCE, openingBalances, -218437));
        assertThat(ledger.balance(openingBalances)).isEqualTo(eur(-218437));
    }

    @Test
    void aCardsOpeningBalanceIsWhatTheUserOwesSoItIsNegative() {
        MvcTestResult created = post(ana, newKey(), """
                {"name": "Credit card", "type": "CREDIT_CARD", "currency": "EUR",
                 "openingBalance": {"amountMinor": -31240, "currency": "EUR"}}""");

        assertThat(created)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$.balance.amountMinor")
                .isEqualTo(-31240);
        UUID card = idOf(created);
        assertThat(postingsOf(ana))
                .as("a credit to the card, a debit to equity")
                .containsExactly(
                        new Line(today(), UserAccounts.OPENING_BALANCE, card, -31240),
                        new Line(today(), UserAccounts.OPENING_BALANCE, openingBalancesOf(ana), 31240));
    }

    @Test
    void anOpeningBalanceOfZeroRecordsNoEntry() {
        assertThat(post(ana, newKey(), """
                        {"name": "Wallet", "type": "CASH", "currency": "EUR",
                         "openingBalance": {"amountMinor": 0, "currency": "EUR"}}"""))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$.balance.amountMinor")
                .isEqualTo(0);
        assertThat(entriesOf(ana)).isZero();
    }

    @Test
    void whenTheOpeningBalanceFailsNoAccountIsLeftAndTheSameKeyCanBeRetried() {
        String key = newKey();
        String body = """
                {"name": "Everyday checking", "type": "CHECKING", "currency": "EUR",
                 "openingBalance": {"amountMinor": 5000, "currency": "EUR"}}""";
        UUID openingBalances = openingBalancesOf(ana);
        setSubtype(openingBalances, "hidden-for-this-test");

        assertThat(post(ana, key, body)).hasFailed().failure().hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(ledger.accounts(ledgerOf(ana)))
                .as("the account was rolled back with the entry")
                .noneMatch(account -> account.kind() == AccountKind.ASSET);
        assertThat(storedKeys(ana)).isZero();

        setSubtype(openingBalances, PersonalLedgers.OPENING_BALANCES);
        assertThat(post(ana, key, body)).hasStatus(HttpStatus.CREATED);
        assertThat(entriesOf(ana)).isOne();
    }

    @Test
    void aRetriedPostGetsTheSameResponseAndCreatesNothingAgain() {
        String key = newKey();
        String body = """
                {"name": "Emergency fund", "type": "SAVINGS", "currency": "EUR",
                 "openingBalance": {"amountMinor": 650000, "currency": "EUR"}}""";

        MvcTestResult first = post(ana, key, body);
        MvcTestResult retry = post(ana, key, body);

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(retry).hasStatus(HttpStatus.CREATED).hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(retry.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());
        assertThat(get(ana))
                .bodyJson()
                .extractingPath("$.items")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .hasSize(1);
        assertThat(entriesOf(ana)).isOne();
        assertThat(ledger.balance(openingBalancesOf(ana))).isEqualTo(eur(-650000));
    }

    @Test
    void withoutAnIdempotencyKeyNothingIsCreated() {
        MvcTestResult result = post(ana, null, """
                {"name": "Wallet", "type": "CASH", "currency": "EUR",
                 "openingBalance": {"amountMinor": 4520, "currency": "EUR"}}""");

        assertBadRequest(result);
        assertNothingCreated(ana);
    }

    @Test
    void theSameKeyForAnotherAccountIsRefused() {
        String key = newKey();
        assertThat(post(ana, key, """
                        {"name": "Wallet", "type": "CASH", "currency": "EUR"}""")).hasStatus(HttpStatus.CREATED);

        assertProblem(post(ana, key, """
                        {"name": "Trip savings", "type": "SAVINGS", "currency": "EUR"}"""), HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_key_reused");
        assertThat(get(ana))
                .bodyJson()
                .extractingPath("$.items[*].name")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("Wallet");
    }

    @Test
    void renamesArchivesAndRestoresAnAccountWhichKeepsItsHistoryAndBalance() {
        UUID savings = create(ana, "SAVINGS", "Savings", 120000L);

        assertThat(patch(ana, savings, """
                        {"name": "  Old savings  "}"""))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.name")
                .isEqualTo("Old savings");

        var archived = assertThat(patch(ana, savings, """
                        {"archived": true}""")).hasStatusOk().bodyJson();
        archived.extractingPath("$.archived").isEqualTo(true);
        archived.extractingPath("$.archivedAt").isNotNull();
        archived.extractingPath("$.balance.amountMinor").isEqualTo(120000);
        var listed = assertThat(get(ana)).bodyJson();
        listed.extractingPath("$.items[0].archived").isEqualTo(true);
        listed.extractingPath("$.items[0].archivedAt").isNotNull();
        listed.extractingPath("$.items[0].balance.amountMinor").isEqualTo(120000);
        assertThat(entriesOf(ana)).as("archiving deletes nothing").isOne();

        var restored = assertThat(patch(ana, savings, """
                        {"archived": false}""")).hasStatusOk().bodyJson();
        restored.extractingPath("$.archived").isEqualTo(false);
        restored.doesNotHavePath("$.archivedAt");
        restored.extractingPath("$.name").isEqualTo("Old savings");
        restored.extractingPath("$.balance.amountMinor").isEqualTo(120000);

        var both = assertThat(patch(ana, savings, """
                        {"name": "Savings", "archived": true}""")).hasStatusOk().bodyJson();
        both.extractingPath("$.name").isEqualTo("Savings");
        both.extractingPath("$.archived").isEqualTo(true);
    }

    @Test
    void listsEachAccountsBalanceAndHowMuchItChangedThisMonth() {
        UUID checking = create(ana, "CHECKING", "Everyday checking", 100000L);
        UUID card = create(ana, "CREDIT_CARD", "Credit card", -31240L);
        UUID savings = create(ana, "SAVINGS", "Emergency fund", null);
        UUID food = categoryOf(ana, "food");
        UUID salary = categoryOf(ana, "salary");
        LocalDate firstOfMonth = today().withDayOfMonth(1);
        postOn(firstOfMonth.minusDays(1), food, checking, 5000);
        postOn(firstOfMonth, checking, salary, 200000);
        postOn(today(), food, card, 2000);
        postOn(today(), savings, checking, 30000);
        postOn(firstOfMonth.plusMonths(1), food, checking, 700);

        var body = assertThat(get(ana)).hasStatusOk().bodyJson();

        body.extractingPath("$.items[*].id")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(checking.toString(), card.toString(), savings.toString());
        body.extractingPath("$.items[*].balance.amountMinor")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(100000 - 5000 + 200000 - 30000 - 700, -31240 - 2000, 30000);
        body.extractingPath("$.items[*].changeThisMonth.amountMinor")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .as("neither the opening balances nor last or next month's entries")
                .containsExactly(200000 - 30000, -2000, 30000);
        body.extractingPath("$.items[*].changeThisMonth.currency")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsOnly("EUR");
    }

    @Test
    void anotherUsersAccountIsNotFound() {
        UUID anasChecking = create(ana, "CHECKING", "Everyday checking", 100000L);
        UUID rui = signedInUser();

        assertNotFound(patch(rui, anasChecking, """
                {"name": "Mine now"}"""));
        assertNotFound(patch(rui, anasChecking, """
                {"archived": true}"""));
        assertThat(get(rui))
                .bodyJson()
                .extractingPath("$.items")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .isEmpty();

        assertThat(ledger.findAccount(anasChecking)).hasValueSatisfying(checking -> {
            assertThat(checking.name()).isEqualTo("Everyday checking");
            assertThat(checking.archived()).isFalse();
        });
    }

    @Test
    void categoriesAndTheOpeningBalancesAccountAreNotFound() {
        for (UUID notAnAccount : List.of(categoryOf(ana, "food"), openingBalancesOf(ana), UUID.randomUUID())) {
            assertNotFound(patch(ana, notAnAccount, """
                    {"archived": true}"""));
        }
        assertThat(ledger.accounts(ledgerOf(ana))).noneMatch(Account::archived);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"name\": \"\", \"type\": \"CHECKING\", \"currency\": \"EUR\"}",
                "{\"name\": \"   \", \"type\": \"CHECKING\", \"currency\": \"EUR\"}",
                "{\"name\": \"\\u2003\", \"type\": \"CHECKING\", \"currency\": \"EUR\"}",
                "{\"name\": \"123456789012345678901234567890123456789012345678901\", \"type\": \"CHECKING\","
                        + " \"currency\": \"EUR\"}",
                "{\"type\": \"CHECKING\", \"currency\": \"EUR\"}",
                "{\"name\": \"Loan\", \"currency\": \"EUR\"}",
                "{\"name\": \"Loan\", \"type\": \"LOAN\", \"currency\": \"EUR\"}",
                "{\"name\": \"Checking\", \"type\": \"CHECKING\"}",
                "{\"name\": \"Checking\", \"type\": \"CHECKING\", \"currency\": \"USD\"}",
                "{\"name\": \"Checking\", \"type\": \"CHECKING\", \"currency\": \"EUR\","
                        + " \"openingBalance\": {\"amountMinor\": 1250, \"currency\": \"USD\"}}",
                "{\"name\": \"Checking\", \"type\": \"CHECKING\", \"currency\": \"EUR\","
                        + " \"openingBalance\": {\"amountMinor\": 1250, \"currency\": \"eur\"}}",
                "{\"name\": \"Checking\", \"type\": \"CHECKING\", \"currency\": \"EUR\","
                        + " \"openingBalance\": {\"amountMinor\": 1250}}",
                "{\"name\": \"Checking\", \"type\": \"CHECKING\", \"currency\": \"EUR\","
                        + " \"openingBalance\": {\"currency\": \"EUR\"}}",
                "{\"name\": \"Checking\", \"type\": \"CHECKING\", \"currency\": \"EUR\","
                        + " \"openingBalance\": {\"amountMinor\": 12.5, \"currency\": \"EUR\"}}",
                "{\"name\": \"Checking\", \"type\": \"CHECKING\", \"currency\": \"EUR\","
                        + " \"openingBalance\": {\"amountMinor\": 9007199254740992, \"currency\": \"EUR\"}}",
                "{\"name\": \"Checking\", \"type\": \"CHECKING\", \"currency\": \"EUR\","
                        + " \"openingBalance\": {\"amountMinor\": -9007199254740992, \"currency\": \"EUR\"}}",
                "not json"
            })
    void refusesAnInvalidNewAccountAndCreatesNothing(String body) {
        assertBadRequest(post(ana, newKey(), body));
        assertNothingCreated(ana);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\": \" \"}", "{\"name\": \"\\u2003\"}", "{\"archived\": null}"})
    void refusesAnInvalidUpdate(String body) {
        assertBadRequest(patch(ana, create(ana, "CASH", "Wallet", null), body));
    }

    @Test
    void withoutASessionEveryEndpointAnswers401() {
        assertThat(mvc.get().uri(ACCOUNTS)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post()
                        .uri(ACCOUNTS)
                        .with(csrf())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Wallet\", \"type\": \"CASH\", \"currency\": \"EUR\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.patch()
                        .uri(ACCOUNTS + "/" + UUID.randomUUID())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    /** One posting of a journal entry, with what the entry says. */
    private record Line(LocalDate occurredOn, String description, UUID accountId, long amountMinor) {}

    /** A user who has signed in: the sign-in transaction published UserSignedIn. */
    private UUID signedInUser() {
        UUID userId = jdbc.sql("INSERT INTO users (email, name) VALUES (:email, 'Accounts Test') RETURNING id")
                .param("email", "accounts-" + UUID.randomUUID() + "@example.com")
                .query(UUID.class)
                .single();
        transactions.executeWithoutResult(status -> events.publishEvent(new UserSignedIn(userId)));
        return userId;
    }

    private UUID create(UUID userId, String type, String name, @Nullable Long openingBalance) {
        String balance = openingBalance == null
                ? ""
                : ", \"openingBalance\": {\"amountMinor\": %d, \"currency\": \"EUR\"}".formatted(openingBalance);
        MvcTestResult created = post(userId, newKey(), """
                {"name": "%s", "type": "%s", "currency": "EUR"%s}""".formatted(name, type, balance));
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return idOf(created);
    }

    /** Moves money from one account to another, as a transaction will (#46). */
    private void postOn(LocalDate occurredOn, UUID to, UUID from, long amountMinor) {
        ledger.post(new NewJournalEntry(
                ledgerOf(ana),
                occurredOn,
                "Test",
                ana,
                List.of(new Posting(to, eur(amountMinor)), new Posting(from, eur(-amountMinor)))));
    }

    private static LocalDate today() {
        return LocalDate.now(PersonalLedgers.TIME_ZONE);
    }

    private UUID ledgerOf(UUID userId) {
        return ledger.findLedger(LedgerOwner.user(userId)).orElseThrow().id();
    }

    private UUID openingBalancesOf(UUID userId) {
        return accountOf(userId, AccountKind.EQUITY, PersonalLedgers.OPENING_BALANCES);
    }

    private UUID categoryOf(UUID userId, String key) {
        return ledger.accounts(ledgerOf(userId)).stream()
                .filter(account -> key.equals(account.subtype()))
                .map(Account::id)
                .findFirst()
                .orElseThrow();
    }

    private UUID accountOf(UUID userId, AccountKind kind, String subtype) {
        return ledger.accounts(ledgerOf(userId)).stream()
                .filter(account -> account.kind() == kind && subtype.equals(account.subtype()))
                .map(Account::id)
                .findFirst()
                .orElseThrow();
    }

    private void setSubtype(UUID accountId, String subtype) {
        jdbc.sql("UPDATE accounts SET subtype = :subtype WHERE id = :id")
                .param("subtype", subtype)
                .param("id", accountId)
                .update();
    }

    private List<Line> postingsOf(UUID userId) {
        return jdbc.sql("""
                        SELECT entry.occurred_on, entry.description, posting.account_id, posting.amount_minor
                        FROM journal_entries entry JOIN postings posting ON posting.entry_id = entry.id
                        WHERE entry.ledger_id = :ledgerId
                        ORDER BY posting.id""")
                .param("ledgerId", ledgerOf(userId))
                .query((rs, rowNum) -> new Line(
                        rs.getObject("occurred_on", LocalDate.class),
                        rs.getString("description"),
                        rs.getObject("account_id", UUID.class),
                        rs.getLong("amount_minor")))
                .list();
    }

    private long entriesOf(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM journal_entries WHERE ledger_id = :ledgerId")
                .param("ledgerId", ledgerOf(userId))
                .query(Long.class)
                .single();
    }

    private long storedKeys(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private void assertNothingCreated(UUID userId) {
        assertThat(get(userId))
                .bodyJson()
                .extractingPath("$.items")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .isEmpty();
        assertThat(entriesOf(userId)).isZero();
    }

    private static UUID idOf(MvcTestResult result) {
        String id =
                JsonPath.read(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.id");
        return UUID.fromString(id);
    }

    private static Money eur(long minor) {
        return new Money(minor, PersonalLedgers.CURRENCY);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private MvcTestResult get(UUID userId) {
        return mvc.get().uri(ACCOUNTS).with(user(userId.toString())).exchange();
    }

    private MvcTestResult post(UUID userId, @Nullable String idempotencyKey, String body) {
        var request = mvc.post()
                .uri(ACCOUNTS)
                .with(user(userId.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.exchange();
    }

    private MvcTestResult patch(UUID userId, UUID accountId, String body) {
        return mvc.patch()
                .uri(ACCOUNTS + "/" + accountId)
                .with(user(userId.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static void assertNotFound(MvcTestResult result) {
        assertProblem(result, HttpStatus.NOT_FOUND, "account_not_found");
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
