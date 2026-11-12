package io.github.muzachio.moneytrail.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PostingRulesTests {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");

    private final UUID ledgerId = UUID.randomUUID();
    private final Account food = account(ledgerId, AccountKind.EXPENSE, "Food", EUR);
    private final Account checking = account(ledgerId, AccountKind.ASSET, "Checking", EUR);
    private final Account travel = account(ledgerId, AccountKind.EXPENSE, "Travel", USD);
    private final Account dollars = account(ledgerId, AccountKind.ASSET, "Dollars", USD);
    private final Map<UUID, Account> accounts = byId(food, checking, travel, dollars);

    @Test
    void acceptsABalancedEntry() {
        NewJournalEntry lunch = entry(posting(food, 1250, EUR), posting(checking, -1250, EUR));

        assertThatCode(() -> check(lunch, accounts)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnEntryBalancedInEachOfItsCurrencies() {
        NewJournalEntry trip = entry(
                posting(food, 1250, EUR),
                posting(travel, 4000, USD),
                posting(checking, -1250, EUR),
                posting(dollars, -4000, USD));

        assertThatCode(() -> check(trip, accounts)).doesNotThrowAnyException();
    }

    @Test
    void acceptsTheLargestAmountsALongHolds() {
        NewJournalEntry huge = entry(posting(food, Long.MAX_VALUE, EUR), posting(checking, -Long.MAX_VALUE, EUR));

        assertThatCode(() -> check(huge, accounts)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAnEntryWithoutPostings() {
        assertRejected(entry(), "too_few_postings");
    }

    @Test
    void rejectsAnEntryWithASinglePosting() {
        assertRejected(entry(posting(food, 1250, EUR)), "too_few_postings");
    }

    @Test
    void rejectsAZeroAmount() {
        assertRejected(
                entry(posting(food, 1250, EUR), posting(checking, 0, EUR), posting(checking, -1250, EUR)),
                "zero_amount");
    }

    @Test
    void rejectsPostingsThatDoNotSumToZero() {
        assertRejected(entry(posting(food, 1250, EUR), posting(checking, -1000, EUR)), "unbalanced_entry");
    }

    @Test
    void rejectsAnEntryBalancedInOneCurrencyButNotInAnother() {
        assertRejected(
                entry(
                        posting(food, 1250, EUR),
                        posting(checking, -1250, EUR),
                        posting(travel, 4000, USD),
                        posting(dollars, -3999, USD)),
                "unbalanced_entry");
    }

    @Test
    void rejectsAmountsInDifferentCurrenciesThatOnlyCancelOutAsNumbers() {
        assertRejected(entry(posting(food, 1000, EUR), posting(dollars, -1000, USD)), "unbalanced_entry");
    }

    @Test
    void rejectsDebitsTooLargeToAddUpWhateverTheirOrder() {
        assertRejected(
                entry(
                        posting(food, Long.MAX_VALUE, EUR),
                        posting(checking, -Long.MAX_VALUE, EUR),
                        posting(food, 1, EUR),
                        posting(checking, -1, EUR)),
                "amount_too_large");
        assertRejected(
                entry(
                        posting(food, Long.MAX_VALUE, EUR),
                        posting(food, 1, EUR),
                        posting(checking, -Long.MAX_VALUE, EUR),
                        posting(checking, -1, EUR)),
                "amount_too_large");
    }

    @Test
    void rejectsAPostingInAnotherCurrencyThanItsAccount() {
        assertRejected(entry(posting(food, 1250, USD), posting(dollars, -1250, USD)), "currency_mismatch");
    }

    @Test
    void rejectsAnAccountFromAnotherLedger() {
        Account someoneElses = account(UUID.randomUUID(), AccountKind.ASSET, "Checking", EUR);

        assertRejected(
                entry(posting(food, 1250, EUR), posting(someoneElses, -1250, EUR)),
                byId(food, someoneElses),
                "account_not_in_ledger",
                HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void rejectsAnAccountThatDoesNotExist() {
        Account missing = account(ledgerId, AccountKind.ASSET, "Gone", EUR);

        assertRejected(
                entry(posting(food, 1250, EUR), posting(missing, -1250, EUR)),
                accounts,
                "account_not_found",
                HttpStatus.NOT_FOUND);
    }

    // The order DefaultLedgerService follows: the amounts first, then the accounts it read.
    private static void check(NewJournalEntry entry, Map<UUID, Account> accounts) {
        PostingRules.checkAmounts(entry.postings());
        PostingRules.checkAccounts(entry, accounts);
    }

    private void assertRejected(NewJournalEntry entry, String code) {
        assertRejected(entry, accounts, code, HttpStatus.UNPROCESSABLE_CONTENT);
    }

    private static void assertRejected(
            NewJournalEntry entry, Map<UUID, Account> accounts, String code, HttpStatus status) {
        assertThatThrownBy(() -> check(entry, accounts)).isInstanceOfSatisfying(LedgerException.class, exception -> {
            assertThat(exception.code()).isEqualTo(code);
            assertThat(exception.status()).isEqualTo(status);
        });
    }

    private NewJournalEntry entry(Posting... postings) {
        return new NewJournalEntry(ledgerId, LocalDate.of(2026, 11, 8), "Lunch", UUID.randomUUID(), List.of(postings));
    }

    private static Posting posting(Account account, long minor, Currency currency) {
        return new Posting(account.id(), new Money(minor, currency));
    }

    private static Account account(UUID ledgerId, AccountKind kind, String name, Currency currency) {
        return new Account(UUID.randomUUID(), ledgerId, kind, null, name, currency, false);
    }

    private static Map<UUID, Account> byId(Account... accounts) {
        return Stream.of(accounts).collect(Collectors.toMap(Account::id, Function.identity()));
    }
}
