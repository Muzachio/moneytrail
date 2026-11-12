package io.github.muzachio.moneytrail.personal.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Every movement, whatever its type and amount (up to the largest the API takes), becomes an entry
 * that balances, and that entry reads back as the same movement.
 */
class MovementProperties {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final long LARGEST_AMOUNT = 9_007_199_254_740_991L;

    private static final Account FOOD = account(AccountKind.EXPENSE, "food");
    private static final Account SALARY = account(AccountKind.INCOME, "salary");
    private static final Account CHECKING = account(AccountKind.ASSET, "checking");
    private static final Account CARD = account(AccountKind.LIABILITY, "credit-card");
    private static final Map<UUID, Account> ACCOUNTS =
            List.of(FOOD, SALARY, CHECKING, CARD).stream().collect(Collectors.toMap(Account::id, Function.identity()));

    @Property
    void thePostingsBalanceAndTheDebitIsTheAmount(@ForAll("movements") Movement movement) {
        List<Posting> postings = movement.postings();

        assertThat(postings).hasSize(2);
        assertThat(postings.get(0).amount()).isEqualTo(movement.amount());
        assertThat(postings.get(0).amount().plus(postings.get(1).amount())).isEqualTo(Money.zero(EUR));
    }

    @Property
    void theEntryReadsBackAsTheSameMovement(@ForAll("movements") Movement movement) {
        JournalEntry entry = new JournalEntry(
                UUID.randomUUID(),
                UUID.randomUUID(),
                movement.occurredOn(),
                movement.description(),
                UUID.randomUUID(),
                Instant.now(),
                null,
                null,
                movement.postings());

        UserTransaction transaction = UserTransaction.of(entry, ACCOUNTS, false).orElseThrow();

        assertThat(transaction.type()).isEqualTo(movement.type());
        assertThat(transaction.amount()).isEqualTo(movement.amount());
        switch (movement) {
            case Movement.Expense expense -> {
                assertThat(transaction.categoryId()).isEqualTo(expense.categoryId());
                assertThat(transaction.accountId()).isEqualTo(expense.accountId());
            }
            case Movement.Income income -> {
                assertThat(transaction.categoryId()).isEqualTo(income.categoryId());
                assertThat(transaction.accountId()).isEqualTo(income.accountId());
            }
            case Movement.Transfer transfer -> {
                assertThat(transaction.fromAccountId()).isEqualTo(transfer.fromAccountId());
                assertThat(transaction.toAccountId()).isEqualTo(transfer.toAccountId());
            }
        }
    }

    @Provide
    Arbitrary<Movement> movements() {
        Arbitrary<Money> amounts =
                Arbitraries.longs().between(1, LARGEST_AMOUNT).map(minor -> new Money(minor, EUR));
        Arbitrary<Account> userAccounts = Arbitraries.of(CHECKING, CARD);
        LocalDate day = LocalDate.of(2026, 10, 22);
        return Arbitraries.oneOf(
                Arbitraries.lazy(() -> userAccounts.flatMap(account ->
                        amounts.map(amount -> new Movement.Expense(FOOD.id(), account.id(), amount, day, "Expense")))),
                Arbitraries.lazy(() -> userAccounts.flatMap(account ->
                        amounts.map(amount -> new Movement.Income(SALARY.id(), account.id(), amount, day, "Income")))),
                Arbitraries.lazy(() -> Arbitraries.of(List.of(CHECKING, CARD), List.of(CARD, CHECKING))
                        .flatMap(pair -> amounts.map(amount -> new Movement.Transfer(
                                pair.get(0).id(), pair.get(1).id(), amount, day, "Transfer")))));
    }

    private static Account account(AccountKind kind, String subtype) {
        return new Account(UUID.randomUUID(), UUID.randomUUID(), kind, subtype, subtype, EUR, null);
    }
}
