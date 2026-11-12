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

/** POST /v1/transactions, GET /v1/transactions/{id} and POST /v1/transactions/{id}/reverse. */
@AutoConfigureMockMvc
class TransactionsApiTests extends PostgresIntegrationTest {

    private static final String TRANSACTIONS = "/v1/transactions";
    private static final String DAY = "2026-10-22";

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
    private UUID food;
    private UUID leisure;
    private UUID salary;

    @BeforeEach
    void signInAnaWithTwoAccounts() {
        ana = signedInUser();
        checking = createAccount(ana, "CHECKING", "Everyday checking", 100000);
        card = createAccount(ana, "CREDIT_CARD", "Credit card", -31240);
        food = categoryOf(ana, "food");
        leisure = categoryOf(ana, "leisure");
        salary = categoryOf(ana, "salary");
    }

    @Test
    void recordsAnExpenseAsADebitToTheCategoryAndACreditToTheAccount() {
        MvcTestResult created = post(ana, newKey(), expense(food, checking, 2750, "  Dinner out  "));

        var body = assertThat(created)
                .hasStatus(HttpStatus.CREATED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson();
        body.extractingPath("$.type").isEqualTo("EXPENSE");
        body.extractingPath("$.amount").isEqualTo(Map.of("amountMinor", 2750, "currency", "EUR"));
        body.extractingPath("$.occurredOn").isEqualTo(DAY);
        body.extractingPath("$.description").isEqualTo("Dinner out");
        body.extractingPath("$.categoryId").isEqualTo(food.toString());
        body.extractingPath("$.accountId").isEqualTo(checking.toString());
        body.doesNotHavePath("$.fromAccountId");
        body.doesNotHavePath("$.toAccountId");
        body.extractingPath("$.recordedAt").isNotNull();
        body.extractingPath("$.edited").isEqualTo(false);
        body.extractingPath("$.reversed").isEqualTo(false);

        UUID id = idOf(created);
        assertThat(postingsOf(id)).containsExactly(new Line(food, 2750), new Line(checking, -2750));
        assertThat(occurredOn(id)).isEqualTo(LocalDate.parse(DAY));
        assertThat(ledger.balance(checking)).isEqualTo(eur(100000 - 2750));
        assertThat(ledger.balance(food)).isEqualTo(eur(2750));
    }

    @Test
    void recordsAnIncomeAsACreditToTheCategoryAndADebitToTheAccount() {
        MvcTestResult created = post(ana, newKey(), income(salary, checking, 187499, "October salary"));

        var body = assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson();
        body.extractingPath("$.type").isEqualTo("INCOME");
        body.extractingPath("$.amount.amountMinor").isEqualTo(187499);
        body.extractingPath("$.categoryId").isEqualTo(salary.toString());
        body.extractingPath("$.accountId").isEqualTo(checking.toString());

        assertThat(postingsOf(idOf(created))).containsExactly(new Line(checking, 187499), new Line(salary, -187499));
        assertThat(ledger.balance(checking)).isEqualTo(eur(100000 + 187499));
        assertThat(ledger.balance(salary)).isEqualTo(eur(-187499));
    }

    @Test
    void recordsATransferAsADebitToTheAccountItGoesToAndACreditToTheOneItComesFrom() {
        MvcTestResult created = post(ana, newKey(), transfer(checking, card, 31240, "Card payment"));

        var body = assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson();
        body.extractingPath("$.type").isEqualTo("TRANSFER");
        body.extractingPath("$.amount.amountMinor").isEqualTo(31240);
        body.extractingPath("$.fromAccountId").isEqualTo(checking.toString());
        body.extractingPath("$.toAccountId").isEqualTo(card.toString());
        body.doesNotHavePath("$.categoryId");
        body.doesNotHavePath("$.accountId");

        assertThat(postingsOf(idOf(created))).containsExactly(new Line(card, 31240), new Line(checking, -31240));
        assertThat(ledger.balance(card)).as("the card is paid off").isEqualTo(eur(0));
        assertThat(ledger.balance(checking)).isEqualTo(eur(100000 - 31240));
    }

    @Test
    void aRetriedPostGetsTheSameResponseAndRecordsNothingAgain() {
        String key = newKey();
        String body = expense(food, card, 1840, "Brunch");

        MvcTestResult first = post(ana, key, body);
        MvcTestResult retry = post(ana, key, body);

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(retry).hasStatus(HttpStatus.CREATED).hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(retry.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());
        assertThat(entriesOf(ana)).isOne();
        assertThat(ledger.balance(card)).isEqualTo(eur(-31240 - 1840));
    }

    @Test
    void withoutAnIdempotencyKeyNothingIsRecorded() {
        assertBadRequest(post(ana, null, expense(food, checking, 980, "Lunch")));
        assertThat(entriesOf(ana)).isZero();
    }

    @Test
    void archivedAccountsAndCategoriesCannotBeUsed() {
        UUID oldCard = createAccount(ana, "CREDIT_CARD", "Old card", 0);
        archive(ana, "/v1/accounts/" + oldCard);
        archive(ana, "/v1/categories/" + leisure);

        assertRefused(post(ana, newKey(), expense(food, oldCard, 1500, "Cinema")), "account_archived");
        assertRefused(post(ana, newKey(), expense(leisure, checking, 1500, "Cinema")), "category_archived");
        assertRefused(post(ana, newKey(), transfer(checking, oldCard, 1500, "Card payment")), "account_archived");
        assertRefused(post(ana, newKey(), transfer(oldCard, checking, 1500, "Refund")), "account_archived");
        assertThat(entriesOf(ana)).isZero();
    }

    @Test
    void anotherUsersAccountsAndCategoriesAndWhatIsNotAnAccountOrACategoryAreRefused() {
        UUID rui = signedInUser();
        UUID ruisChecking = createAccount(rui, "CHECKING", "Rui's checking", 50000);
        UUID ruisFood = categoryOf(rui, "food");
        UUID openingBalances = ledger.accounts(ledgerOf(ana)).stream()
                .filter(account -> account.kind() == AccountKind.EQUITY)
                .map(Account::id)
                .findFirst()
                .orElseThrow();

        assertRefused(post(ana, newKey(), expense(food, ruisChecking, 980, "Lunch")), "account_not_found");
        assertRefused(post(ana, newKey(), expense(ruisFood, checking, 980, "Lunch")), "category_not_found");
        assertRefused(post(ana, newKey(), transfer(ruisChecking, checking, 980, "Mine now")), "account_not_found");
        assertRefused(post(ana, newKey(), expense(food, leisure, 980, "Lunch")), "account_not_found");
        assertRefused(post(ana, newKey(), expense(checking, card, 980, "Lunch")), "category_not_found");
        assertRefused(post(ana, newKey(), transfer(openingBalances, checking, 980, "Free money")), "account_not_found");
        assertRefused(post(ana, newKey(), expense(UUID.randomUUID(), checking, 980, "Lunch")), "category_not_found");
        assertRefused(post(ana, newKey(), expense(salary, checking, 980, "Lunch")), "category_kind_mismatch");
        assertRefused(post(ana, newKey(), income(food, checking, 980, "Refund")), "category_kind_mismatch");

        assertThat(entriesOf(ana)).isZero();
        assertThat(ledger.balance(ruisChecking)).isEqualTo(eur(50000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "XYZ"})
    void anAmountInAnotherCurrencyThanTheAccountsIsRefused(String currency) {
        String body = expense(food, checking, 980, "Lunch").replace("\"EUR\"", "\"" + currency + "\"");

        assertRefused(post(ana, newKey(), body), "currency_mismatch");
        assertThat(entriesOf(ana)).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // The amount: above zero, whole, a JavaScript-safe integer, with a currency code.
                "{'type': 'EXPENSE', 'amount': {'amountMinor': 0, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Lunch', 'categoryId': '{food}', 'accountId': '{checking}'}",
                "{'type': 'EXPENSE', 'amount': {'amountMinor': -980, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Lunch', 'categoryId': '{food}', 'accountId': '{checking}'}",
                "{'type': 'EXPENSE', 'amount': {'amountMinor': 9.8, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Lunch', 'categoryId': '{food}', 'accountId': '{checking}'}",
                "{'type': 'EXPENSE', 'amount': {'amountMinor': 9007199254740992, 'currency': 'EUR'},"
                        + " 'occurredOn': '2026-10-22', 'description': 'Lunch', 'categoryId': '{food}',"
                        + " 'accountId': '{checking}'}",
                "{'type': 'EXPENSE', 'amount': {'amountMinor': 980, 'currency': 'eur'}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Lunch', 'categoryId': '{food}', 'accountId': '{checking}'}",
                "{'type': 'EXPENSE', 'amount': {'amountMinor': 980}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Lunch', 'categoryId': '{food}', 'accountId': '{checking}'}",
                "{'type': 'EXPENSE', 'occurredOn': '2026-10-22', 'description': 'Lunch', 'categoryId': '{food}',"
                        + " 'accountId': '{checking}'}",
                // The date and the description.
                "{'type': 'EXPENSE', 'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'description': 'Lunch',"
                        + " 'categoryId': '{food}', 'accountId': '{checking}'}",
                "{'type': 'EXPENSE', 'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'occurredOn': '2026-02-30',"
                        + " 'description': 'Lunch', 'categoryId': '{food}', 'accountId': '{checking}'}",
                "{'type': 'EXPENSE', 'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'categoryId': '{food}', 'accountId': '{checking}'}",
                "{'type': 'EXPENSE', 'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': '   ', 'categoryId': '{food}', 'accountId': '{checking}'}",
                "{'type': 'EXPENSE', 'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': '\\u2003', 'categoryId': '{food}', 'accountId': '{checking}'}",
                // The type and the properties it takes.
                "{'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Lunch', 'categoryId': '{food}', 'accountId': '{checking}'}",
                "{'type': 'REFUND', 'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Lunch', 'categoryId': '{food}', 'accountId': '{checking}'}",
                "{'type': 'EXPENSE', 'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Lunch', 'accountId': '{checking}'}",
                "{'type': 'INCOME', 'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Refund', 'categoryId': '{salary}'}",
                "{'type': 'EXPENSE', 'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Lunch', 'categoryId': '{food}', 'accountId': '{checking}',"
                        + " 'toAccountId': '{card}'}",
                "{'type': 'TRANSFER', 'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Savings', 'fromAccountId': '{checking}'}",
                "{'type': 'TRANSFER', 'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Savings', 'fromAccountId': '{checking}', 'toAccountId': '{card}',"
                        + " 'categoryId': '{food}'}",
                "{'type': 'TRANSFER', 'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Nowhere', 'fromAccountId': '{checking}', 'toAccountId': '{checking}'}",
                "{'type': 'EXPENSE', 'amount': {'amountMinor': 980, 'currency': 'EUR'}, 'occurredOn': '2026-10-22',"
                        + " 'description': 'Lunch', 'categoryId': 'food', 'accountId': '{checking}'}",
                "not json"
            })
    void refusesAnInvalidTransactionAndRecordsNothing(String template) {
        assertBadRequest(post(ana, newKey(), json(template)));
        assertThat(entriesOf(ana)).isZero();
    }

    @Test
    void getsATransactionWithItsPostingsAndItsTrail() {
        UUID dinner = record(ana, expense(food, card, 2570, "Dinner out"));

        var body = assertThat(get(ana, dinner)).hasStatusOk().bodyJson();

        body.extractingPath("$.transaction.id").isEqualTo(dinner.toString());
        body.extractingPath("$.transaction.type").isEqualTo("EXPENSE");
        body.extractingPath("$.transaction.categoryId").isEqualTo(food.toString());
        body.extractingPath("$.transaction.accountId").isEqualTo(card.toString());
        body.extractingPath("$.transaction.reversed").isEqualTo(false);
        body.extractingPath("$.postings")
                .isEqualTo(List.of(
                        Map.of("accountId", food.toString(), "amount", Map.of("amountMinor", 2570, "currency", "EUR")),
                        Map.of(
                                "accountId",
                                card.toString(),
                                "amount",
                                Map.of("amountMinor", -2570, "currency", "EUR"))));
        body.extractingPath("$.trail[*].action")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("RECORDED");
        body.extractingPath("$.trail[0].entryId").isEqualTo(dinner.toString());
        body.extractingPath("$.trail[0].amount.amountMinor").isEqualTo(2570);
        body.extractingPath("$.trail[0].type").isEqualTo("EXPENSE");
        body.doesNotHavePath("$.trail[0].reversesEntryId");
        body.extractingPath("$.currentId").isEqualTo(dinner.toString());
    }

    @Test
    void reversingPostsTheExactOppositeOnTheSameDayLinkedToTheTransaction() {
        UUID dinner = record(ana, expense(food, card, 2570, "Dinner out"));

        MvcTestResult reversed = reverse(ana, dinner, newKey(), null);

        var body = assertThat(reversed).hasStatusOk().bodyJson();
        body.extractingPath("$.transaction.id").isEqualTo(dinner.toString());
        body.extractingPath("$.transaction.reversed").isEqualTo(true);
        body.extractingPath("$.trail[*].action")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("RECORDED", "REVERSED");
        body.extractingPath("$.trail[1].reversesEntryId").isEqualTo(dinner.toString());
        body.extractingPath("$.trail[1].amount.amountMinor").isEqualTo(2570);
        body.extractingPath("$.trail[1].type").isEqualTo("EXPENSE");
        body.doesNotHavePath("$.currentId");

        UUID reversal = UUID.fromString(read(reversed, "$.trail[1].entryId"));
        assertThat(postingsOf(reversal)).containsExactly(new Line(food, -2570), new Line(card, 2570));
        assertThat(occurredOn(reversal)).isEqualTo(LocalDate.parse(DAY));
        assertThat(reversesEntryOf(reversal)).isEqualTo(dinner);
        assertThat(ledger.balance(card)).isEqualTo(eur(-31240));
        assertThat(ledger.balance(food)).isEqualTo(eur(0));
        assertThat(get(ana, dinner))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.transaction.reversed")
                .isEqualTo(true);
    }

    @Test
    void anEmptyJsonBodyAlsoReversesWithoutACorrection() {
        UUID dinner = record(ana, expense(food, card, 2570, "Dinner out"));

        assertThat(reverse(ana, dinner, newKey(), "{}"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.trail[*].action")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("RECORDED", "REVERSED");
    }

    @Test
    void aTransactionIsReversedOnlyOnce() {
        UUID dinner = record(ana, expense(food, card, 2570, "Dinner out"));
        assertThat(reverse(ana, dinner, newKey(), null)).hasStatusOk();

        assertProblem(reverse(ana, dinner, newKey(), null), HttpStatus.CONFLICT, "transaction_already_reversed");
        assertProblem(
                reverse(ana, dinner, newKey(), correction(expense(food, card, 2750, "Dinner out"))),
                HttpStatus.CONFLICT,
                "transaction_already_reversed");
        assertThat(entriesOf(ana)).as("the transaction and its one reversal").isEqualTo(2);
    }

    @Test
    void aReversalMayPostToAnArchivedAccountAndCategory() {
        UUID oldCard = createAccount(ana, "CREDIT_CARD", "Old card", 0);
        UUID cinema = record(ana, expense(leisure, oldCard, 1500, "Cinema"));
        archive(ana, "/v1/accounts/" + oldCard);
        archive(ana, "/v1/categories/" + leisure);

        assertThat(reverse(ana, cinema, newKey(), null)).hasStatusOk();
        assertThat(ledger.balance(oldCard)).isEqualTo(eur(0));
        assertThat(ledger.balance(leisure)).isEqualTo(eur(0));
    }

    @Test
    void editingReversesTheTransactionAndRecordsItsCorrectionTogether() {
        UUID dinner = record(ana, expense(food, card, 2570, "Dinner out"));

        MvcTestResult edited = reverse(
                ana, dinner, newKey(), correction(expense(leisure, checking, 2750, "Dinner and a show", "2026-10-23")));

        var body = assertThat(edited).hasStatusOk().bodyJson();
        UUID corrected = UUID.fromString(read(edited, "$.transaction.id"));
        assertThat(corrected).isNotEqualTo(dinner);
        body.extractingPath("$.transaction.edited").isEqualTo(true);
        body.extractingPath("$.transaction.reversed").isEqualTo(false);
        body.extractingPath("$.transaction.amount.amountMinor").isEqualTo(2750);
        body.extractingPath("$.transaction.categoryId").isEqualTo(leisure.toString());
        body.extractingPath("$.transaction.accountId").isEqualTo(checking.toString());
        body.extractingPath("$.transaction.occurredOn").isEqualTo("2026-10-23");
        body.extractingPath("$.transaction.description").isEqualTo("Dinner and a show");
        body.extractingPath("$.trail[*].action")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("RECORDED", "REVERSED", "CORRECTED");
        body.extractingPath("$.trail[*].amount.amountMinor")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(2570, 2570, 2750);
        body.extractingPath("$.trail[0].entryId").isEqualTo(dinner.toString());
        body.extractingPath("$.trail[2].entryId").isEqualTo(corrected.toString());
        body.extractingPath("$.currentId").isEqualTo(corrected.toString());

        assertThat(replacesEntryOf(corrected)).isEqualTo(dinner);
        assertThat(postingsOf(corrected)).containsExactly(new Line(leisure, 2750), new Line(checking, -2750));
        assertThat(ledger.balance(card)).isEqualTo(eur(-31240));
        assertThat(ledger.balance(food)).isEqualTo(eur(0));
        assertThat(ledger.balance(leisure)).isEqualTo(eur(2750));
        assertThat(ledger.balance(checking)).isEqualTo(eur(100000 - 2750));

        var original = assertThat(get(ana, dinner)).hasStatusOk().bodyJson();
        original.extractingPath("$.transaction.reversed").isEqualTo(true);
        original.extractingPath("$.transaction.edited").isEqualTo(false);
        original.extractingPath("$.transaction.amount.amountMinor").isEqualTo(2570);
        original.extractingPath("$.trail").isEqualTo(JsonPath.read(bodyOf(edited), "$.trail"));
        original.extractingPath("$.currentId").isEqualTo(corrected.toString());
    }

    @Test
    void anEditedTransactionKeepsItsWholeTrailAndOnlyItsCurrentVersionCanChange() {
        UUID first = record(ana, expense(food, card, 2570, "Dinner out"));
        UUID second = idOf(
                reverse(ana, first, newKey(), correction(expense(food, card, 2750, "Dinner out"))), "$.transaction.id");
        MvcTestResult thirdEdit =
                reverse(ana, second, newKey(), correction(income(salary, checking, 2750, "Dinner paid back")));
        UUID third = idOf(thirdEdit, "$.transaction.id");

        var body = assertThat(thirdEdit).hasStatusOk().bodyJson();
        body.extractingPath("$.transaction.type").isEqualTo("INCOME");
        body.extractingPath("$.trail[*].action")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("RECORDED", "REVERSED", "CORRECTED", "REVERSED", "CORRECTED");
        body.extractingPath("$.trail[*].type")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("EXPENSE", "EXPENSE", "EXPENSE", "EXPENSE", "INCOME");
        body.extractingPath("$.trail[3].reversesEntryId").isEqualTo(second.toString());
        body.extractingPath("$.currentId").isEqualTo(third.toString());

        assertProblem(reverse(ana, first, newKey(), null), HttpStatus.CONFLICT, "transaction_already_reversed");
        assertProblem(
                reverse(ana, second, newKey(), correction(expense(food, card, 100, "Snack"))),
                HttpStatus.CONFLICT,
                "transaction_already_reversed");

        assertThat(reverse(ana, third, newKey(), null)).hasStatusOk().bodyJson().doesNotHavePath("$.currentId");
        assertThat(get(ana, first))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.trail")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .hasSize(6);
        assertThat(ledger.balance(card)).isEqualTo(eur(-31240));
        assertThat(ledger.balance(checking)).isEqualTo(eur(100000));
    }

    @Test
    void whenTheCorrectionIsRefusedNothingIsReversedAndTheSameKeyCanBeRetried() {
        UUID dinner = record(ana, expense(food, card, 2570, "Dinner out"));
        archive(ana, "/v1/categories/" + leisure);
        String key = newKey();
        String body = correction(expense(leisure, card, 2750, "Cinema"));

        assertRefused(reverse(ana, dinner, key, body), "category_archived");
        assertThat(entriesOf(ana)).isOne();
        assertThat(get(ana, dinner))
                .bodyJson()
                .extractingPath("$.transaction.reversed")
                .isEqualTo(false);

        assertThat(patch(ana, "/v1/categories/" + leisure, "{\"archived\": false}"))
                .hasStatusOk();
        assertThat(reverse(ana, dinner, key, body)).hasStatusOk();
        assertThat(ledger.balance(leisure)).isEqualTo(eur(2750));
    }

    @Test
    void aCorrectionMayKeepAnArchivedAccountOrCategoryButNotPickAnother() {
        UUID oldCard = createAccount(ana, "CREDIT_CARD", "Old card", 0);
        UUID otherOldCard = createAccount(ana, "CREDIT_CARD", "Other old card", 0);
        UUID cinema = record(ana, expense(leisure, oldCard, 1500, "Cinema"));
        archive(ana, "/v1/accounts/" + oldCard);
        archive(ana, "/v1/accounts/" + otherOldCard);
        archive(ana, "/v1/categories/" + leisure);

        assertRefused(
                reverse(ana, cinema, newKey(), correction(expense(leisure, otherOldCard, 1250, "Cinema"))),
                "account_archived");
        MvcTestResult kept = reverse(ana, cinema, newKey(), correction(expense(leisure, oldCard, 1250, "Cinema")));

        assertThat(kept).hasStatusOk();
        assertThat(ledger.balance(oldCard)).isEqualTo(eur(-1250));
        assertThat(ledger.balance(leisure)).isEqualTo(eur(1250));
    }

    @Test
    void aRetriedReversalGetsTheSameResponseAndReversesOnce() {
        UUID dinner = record(ana, expense(food, card, 2570, "Dinner out"));
        String key = newKey();
        String body = correction(expense(food, card, 2750, "Dinner out"));

        MvcTestResult first = reverse(ana, dinner, key, body);
        MvcTestResult retry = reverse(ana, dinner, key, body);

        assertThat(first).hasStatusOk();
        assertThat(retry).hasStatusOk();
        assertThat(retry.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());
        assertThat(entriesOf(ana))
                .as("the transaction, its reversal and its correction")
                .isEqualTo(3);
        assertThat(ledger.balance(card)).isEqualTo(eur(-31240 - 2750));
    }

    @Test
    void anotherUsersTransactionIsNotFound() {
        UUID dinner = record(ana, expense(food, card, 2570, "Dinner out"));
        UUID rui = signedInUser();
        UUID ruisChecking = createAccount(rui, "CHECKING", "Rui's checking", 0);
        UUID ruisFood = categoryOf(rui, "food");

        assertNotFound(get(rui, dinner));
        assertNotFound(reverse(rui, dinner, newKey(), null));
        assertNotFound(reverse(rui, dinner, newKey(), correction(expense(ruisFood, ruisChecking, 1, "Mine now"))));

        assertThat(get(ana, dinner))
                .bodyJson()
                .extractingPath("$.transaction.reversed")
                .isEqualTo(false);
        assertThat(ledger.balance(card)).isEqualTo(eur(-31240 - 2570));
    }

    @Test
    void entriesThatAreNotTransactionsAreNotFound() {
        UUID dinner = record(ana, expense(food, card, 2570, "Dinner out"));
        UUID reversal = UUID.fromString(read(reverse(ana, dinner, newKey(), null), "$.trail[1].entryId"));
        UUID openingBalance = jdbc.sql("""
                        SELECT DISTINCT entry_id FROM postings
                        WHERE account_id = :checking AND entry_id <> :dinner AND entry_id <> :reversal""")
                .param("checking", checking)
                .param("dinner", dinner)
                .param("reversal", reversal)
                .query(UUID.class)
                .single();

        for (UUID notATransaction : List.of(reversal, openingBalance, UUID.randomUUID())) {
            assertNotFound(get(ana, notATransaction));
            assertNotFound(reverse(ana, notATransaction, newKey(), null));
        }
        assertThat(ledger.balance(checking)).isEqualTo(eur(100000));
    }

    @Test
    void withoutASessionEveryEndpointAnswers401() {
        UUID dinner = record(ana, expense(food, card, 2570, "Dinner out"));

        assertThat(mvc.post()
                        .uri(TRANSACTIONS)
                        .with(csrf())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expense(food, card, 100, "Snack")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri(TRANSACTIONS + "/" + dinner)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post()
                        .uri(TRANSACTIONS + "/" + dinner + "/reverse")
                        .with(csrf())
                        .header("Idempotency-Key", newKey()))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(entriesOf(ana)).isOne();
    }

    /** One posting of a journal entry. */
    private record Line(UUID accountId, long amountMinor) {}

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
        MvcTestResult created = mvc.post()
                .uri("/v1/accounts")
                .with(user(userId.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "type": "%s", "currency": "EUR",
                         "openingBalance": {"amountMinor": %d, "currency": "EUR"}}""".formatted(name, type, openingBalance))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return idOf(created);
    }

    private void archive(UUID userId, String path) {
        assertThat(patch(userId, path, "{\"archived\": true}")).hasStatusOk();
    }

    private UUID record(UUID userId, String body) {
        MvcTestResult created = post(userId, newKey(), body);
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return idOf(created);
    }

    private static String expense(UUID categoryId, UUID accountId, long amountMinor, String description) {
        return expense(categoryId, accountId, amountMinor, description, DAY);
    }

    private static String expense(
            UUID categoryId, UUID accountId, long amountMinor, String description, String occurredOn) {
        return """
                {"type": "EXPENSE", "amount": {"amountMinor": %d, "currency": "EUR"}, "occurredOn": "%s",
                 "description": "%s", "categoryId": "%s", "accountId": "%s"}""".formatted(amountMinor, occurredOn, description, categoryId, accountId);
    }

    private static String income(UUID categoryId, UUID accountId, long amountMinor, String description) {
        return """
                {"type": "INCOME", "amount": {"amountMinor": %d, "currency": "EUR"}, "occurredOn": "%s",
                 "description": "%s", "categoryId": "%s", "accountId": "%s"}""".formatted(amountMinor, DAY, description, categoryId, accountId);
    }

    private static String transfer(UUID fromAccountId, UUID toAccountId, long amountMinor, String description) {
        return """
                {"type": "TRANSFER", "amount": {"amountMinor": %d, "currency": "EUR"}, "occurredOn": "%s",
                 "description": "%s", "fromAccountId": "%s", "toAccountId": "%s"}""".formatted(amountMinor, DAY, description, fromAccountId, toAccountId);
    }

    private static String correction(String replacement) {
        return "{\"replacement\": " + replacement + "}";
    }

    /** Single quotes for readable templates, and the test's accounts in place of their names. */
    private String json(String template) {
        return template.replace('\'', '"')
                .replace("{food}", food.toString())
                .replace("{salary}", salary.toString())
                .replace("{checking}", checking.toString())
                .replace("{card}", card.toString());
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

    private List<Line> postingsOf(UUID entryId) {
        return jdbc.sql("SELECT account_id, amount_minor FROM postings WHERE entry_id = :entryId ORDER BY id")
                .param("entryId", entryId)
                .query((rs, rowNum) -> new Line(rs.getObject("account_id", UUID.class), rs.getLong("amount_minor")))
                .list();
    }

    private LocalDate occurredOn(UUID entryId) {
        return jdbc.sql("SELECT occurred_on FROM journal_entries WHERE id = :id")
                .param("id", entryId)
                .query(LocalDate.class)
                .single();
    }

    private UUID reversesEntryOf(UUID entryId) {
        return jdbc.sql("SELECT reverses_entry_id FROM journal_entries WHERE id = :id")
                .param("id", entryId)
                .query(UUID.class)
                .single();
    }

    private UUID replacesEntryOf(UUID entryId) {
        return jdbc.sql("SELECT replaces_entry_id FROM journal_entries WHERE id = :id")
                .param("id", entryId)
                .query(UUID.class)
                .single();
    }

    /**
     * The journal entries of the user's ledger, reversals included, but not the opening balances that
     * the accounts of the set-up posted.
     */
    private long entriesOf(UUID userId) {
        return jdbc.sql("""
                        SELECT count(*) FROM journal_entries entry
                        WHERE entry.ledger_id = :ledgerId AND entry.description <> :openingBalance""")
                .param("ledgerId", ledgerOf(userId))
                .param("openingBalance", UserAccounts.OPENING_BALANCE)
                .query(Long.class)
                .single();
    }

    private static UUID idOf(MvcTestResult result) {
        return idOf(result, "$.id");
    }

    private static UUID idOf(MvcTestResult result, String path) {
        return UUID.fromString(read(result, path));
    }

    private static String read(MvcTestResult result, String path) {
        return JsonPath.read(bodyOf(result), path);
    }

    private static String bodyOf(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static Money eur(long minor) {
        return new Money(minor, PersonalLedgers.CURRENCY);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private MvcTestResult get(UUID userId, UUID transactionId) {
        return mvc.get()
                .uri(TRANSACTIONS + "/" + transactionId)
                .with(user(userId.toString()))
                .exchange();
    }

    private MvcTestResult post(UUID userId, @Nullable String idempotencyKey, String body) {
        var request = mvc.post()
                .uri(TRANSACTIONS)
                .with(user(userId.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.exchange();
    }

    /** Without a body when {@code body} is null: no content and no content type, as a client sends it. */
    private MvcTestResult reverse(UUID userId, UUID transactionId, String idempotencyKey, @Nullable String body) {
        var request = mvc.post()
                .uri(TRANSACTIONS + "/" + transactionId + "/reverse")
                .with(user(userId.toString()))
                .with(csrf())
                .header("Idempotency-Key", idempotencyKey);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private MvcTestResult patch(UUID userId, String path, String body) {
        return mvc.patch()
                .uri(path)
                .with(user(userId.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static void assertNotFound(MvcTestResult result) {
        assertProblem(result, HttpStatus.NOT_FOUND, "transaction_not_found");
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
