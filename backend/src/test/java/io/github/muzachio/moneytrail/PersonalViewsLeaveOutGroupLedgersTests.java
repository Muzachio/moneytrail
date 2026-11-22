package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.jayway.jsonpath.JsonPath;
import io.github.muzachio.moneytrail.identity.UserSignedIn;
import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.Ledger;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Someone in a group has two ledgers with their movements: their own, and the group's, whose entries
 * they record under their own id. Their accounts, categories, transactions and reports come from
 * their own ledger only (ADR-0012). Every name and amount is invented.
 */
@AutoConfigureMockMvc
class PersonalViewsLeaveOutGroupLedgersTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalDate OCTOBER_20 = LocalDate.of(2026, 10, 20);

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
    private UUID lunch;
    private Ledger group;
    private Account anaInTheGroup;
    private Account joaoInTheGroup;
    private JournalEntry dinner;

    @BeforeEach
    void anaHasHerOwnLedgerAndIsInAGroup() {
        ana = signedInUser();
        checking = createAccount("Everyday checking", 100000);
        lunch = record("""
                {"type": "EXPENSE", "amount": {"amountMinor": 1250, "currency": "EUR"}, "occurredOn": "%s",
                 "description": "Lunch", "categoryId": "%s", "accountId": "%s"}""".formatted(OCTOBER_20, categoryOf("food"), checking));

        group = ledger.openLedger(LedgerOwner.group(UUID.randomUUID()));
        anaInTheGroup = member("Ana");
        joaoInTheGroup = member("João");
        // Ana pays 30.00 for dinner on the same day, split equally with João, and records it.
        dinner = ledger.post(new NewJournalEntry(
                group.id(),
                OCTOBER_20,
                "Dinner",
                ana,
                List.of(posting(anaInTheGroup, 3000), posting(anaInTheGroup, -1500), posting(joaoInTheGroup, -1500))));
    }

    @Test
    void herAccountsAreOnlyHerOwn() {
        assertThat(get("/v1/accounts"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.items[*].id")
                .isEqualTo(List.of(checking.toString()));
    }

    @Test
    void herCategoriesAreOnlyHerOwn() {
        List<String> ownCategories =
                ledger
                        .accounts(ledger.findLedger(LedgerOwner.user(ana))
                                .orElseThrow()
                                .id())
                        .stream()
                        .filter(account ->
                                account.kind() == AccountKind.EXPENSE || account.kind() == AccountKind.INCOME)
                        .map(account -> account.id().toString())
                        .toList();

        List<String> listed = JsonPath.read(bodyOf(get("/v1/categories")), "$.items[*].id");

        assertThat(listed).isNotEmpty().containsExactlyInAnyOrderElementsOf(ownCategories);
    }

    @Test
    void herTransactionsAndTheirTotalsAreOnlyHerOwn() {
        var body =
                assertThat(get("/v1/transactions?month=2026-10")).hasStatusOk().bodyJson();

        body.extractingPath("$.items[*].id").isEqualTo(List.of(lunch.toString()));
        body.extractingPath("$.summary.count").isEqualTo(1);
        body.extractingPath("$.summary.spending.amountMinor").isEqualTo(1250);
        assertThat(get("/v1/transactions/" + dinner.id())).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void herReportsAreOnlyHerOwn() {
        assertThat(get("/v1/reports/spending?from=2026-10-01&to=2026-10-31&groupBy=category"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.spending.amountMinor")
                .isEqualTo(1250);
        assertThat(get("/v1/reports/spending?from=2026-10-01&to=2026-10-31&groupBy=month"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.months[*].spending.amountMinor")
                .isEqualTo(List.of(1250));
    }

    private UUID signedInUser() {
        UUID userId = jdbc.sql("INSERT INTO users (email, name) VALUES (:email, 'Groups Test') RETURNING id")
                .param("email", "groups-" + UUID.randomUUID() + "@example.com")
                .query(UUID.class)
                .single();
        transactions.executeWithoutResult(status -> events.publishEvent(new UserSignedIn(userId)));
        return userId;
    }

    private UUID createAccount(String name, long openingBalance) {
        return idOf(created(mvc.post()
                .uri("/v1/accounts")
                .with(user(ana.toString()))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "type": "CHECKING", "currency": "EUR",
                         "openingBalance": {"amountMinor": %d, "currency": "EUR"}}""".formatted(name, openingBalance))
                .exchange()));
    }

    private UUID record(String body) {
        return idOf(created(mvc.post()
                .uri("/v1/transactions")
                .with(user(ana.toString()))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange()));
    }

    private UUID categoryOf(String key) {
        UUID ledgerId = ledger.findLedger(LedgerOwner.user(ana)).orElseThrow().id();
        return ledger.accounts(ledgerId).stream()
                .filter(account -> key.equals(account.subtype()))
                .map(Account::id)
                .findFirst()
                .orElseThrow();
    }

    private Account member(String name) {
        return ledger.openAccount(new NewAccount(group.id(), AccountKind.GROUP_MEMBER, null, name, EUR));
    }

    private MvcTestResult get(String uri) {
        return mvc.get().uri(uri).with(user(ana.toString())).exchange();
    }

    private static Posting posting(Account account, long minor) {
        return new Posting(account.id(), new Money(minor, EUR));
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
}
