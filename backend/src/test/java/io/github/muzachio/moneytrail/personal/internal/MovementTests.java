package io.github.muzachio.moneytrail.personal.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.DomainException;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The posting rule of each type of movement (docs/architecture.md, "The ledger"). */
class MovementTests {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 22);

    private final UUID food = UUID.randomUUID();
    private final UUID salary = UUID.randomUUID();
    private final UUID checking = UUID.randomUUID();
    private final UUID savings = UUID.randomUUID();

    @Test
    void anExpenseDebitsTheCategoryAndCreditsTheAccount() {
        Movement expense = new Movement.Expense(food, checking, eur(2750), DAY, "Dinner out");

        assertThat(expense.postings()).containsExactly(new Posting(food, eur(2750)), new Posting(checking, eur(-2750)));
        assertThat(expense.type()).isEqualTo(UserTransaction.Type.EXPENSE);
    }

    @Test
    void anIncomeCreditsTheCategoryAndDebitsTheAccount() {
        Movement income = new Movement.Income(salary, checking, eur(187499), DAY, "October salary");

        assertThat(income.postings())
                .containsExactly(new Posting(checking, eur(187499)), new Posting(salary, eur(-187499)));
        assertThat(income.type()).isEqualTo(UserTransaction.Type.INCOME);
    }

    @Test
    void aTransferDebitsTheAccountItGoesToAndCreditsTheOneItComesFrom() {
        Movement transfer = new Movement.Transfer(checking, savings, eur(20000), DAY, "Emergency fund top-up");

        assertThat(transfer.postings())
                .containsExactly(new Posting(savings, eur(20000)), new Posting(checking, eur(-20000)));
        assertThat(transfer.type()).isEqualTo(UserTransaction.Type.TRANSFER);
    }

    @Test
    void theEntryKeepsTheDayTheDescriptionAndWhoRecordedIt() {
        UUID ledgerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Movement expense = new Movement.Expense(food, checking, eur(980), DAY, "  Lunch ");

        var entry = expense.toEntry(ledgerId, userId);

        assertThat(entry.ledgerId()).isEqualTo(ledgerId);
        assertThat(entry.occurredOn()).isEqualTo(DAY);
        assertThat(entry.description()).isEqualTo("Lunch");
        assertThat(entry.createdBy()).isEqualTo(userId);
        assertThat(entry.postings()).isEqualTo(expense.postings());
    }

    @Test
    void refusesAnAmountThatIsNotAboveZero() {
        assertBadRequest(() -> new Movement.Expense(food, checking, eur(0), DAY, "Lunch"));
        assertBadRequest(() -> new Movement.Income(salary, checking, eur(-1), DAY, "Salary"));
        assertBadRequest(() -> new Movement.Transfer(checking, savings, eur(0), DAY, "Savings"));
    }

    @Test
    void refusesADescriptionOfOnlySpaces() {
        assertBadRequest(() -> new Movement.Expense(food, checking, eur(980), DAY, "  \t"));
    }

    @Test
    void refusesATransferToTheSameAccount() {
        assertBadRequest(() -> new Movement.Transfer(checking, checking, eur(1000), DAY, "Nowhere"));
    }

    private static void assertBadRequest(Runnable creation) {
        assertThatThrownBy(creation::run)
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("bad_request"));
    }

    private static Money eur(long minor) {
        return new Money(minor, EUR);
    }
}
