package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the user records: an expense, an income or a transfer, and the postings each one becomes
 * (docs/architecture.md, "The ledger"). The amount is always above zero; the type says in which
 * direction it moves, so every movement balances whatever the amount.
 */
sealed interface Movement {

    UserTransaction.Type type();

    Money amount();

    LocalDate occurredOn();

    String description();

    /** The postings, debit (+) first. They sum to zero. */
    List<Posting> postings();

    default NewJournalEntry toEntry(UUID ledgerId, UUID recordedBy) {
        return new NewJournalEntry(ledgerId, occurredOn(), description(), recordedBy, postings());
    }

    /** Money spent: the category's spending goes up, and the account pays it. */
    record Expense(UUID categoryId, UUID accountId, Money amount, LocalDate occurredOn, String description)
            implements Movement {

        public Expense {
            requirePositive(amount);
            description = clean(description);
        }

        @Override
        public UserTransaction.Type type() {
            return UserTransaction.Type.EXPENSE;
        }

        /** {@code +category −account}. */
        @Override
        public List<Posting> postings() {
            return List.of(new Posting(categoryId, amount), new Posting(accountId, amount.negate()));
        }
    }

    /** Money received: the account gets it, and the category's income (a credit) goes up. */
    record Income(UUID categoryId, UUID accountId, Money amount, LocalDate occurredOn, String description)
            implements Movement {

        public Income {
            requirePositive(amount);
            description = clean(description);
        }

        @Override
        public UserTransaction.Type type() {
            return UserTransaction.Type.INCOME;
        }

        /** {@code −category +account}. */
        @Override
        public List<Posting> postings() {
            return List.of(new Posting(accountId, amount), new Posting(categoryId, amount.negate()));
        }
    }

    /**
     * Money moved between two of the user's accounts, such as savings or paying off a credit card.
     * Neither spending nor income.
     */
    record Transfer(UUID fromAccountId, UUID toAccountId, Money amount, LocalDate occurredOn, String description)
            implements Movement {

        public Transfer {
            requirePositive(amount);
            description = clean(description);
            if (fromAccountId.equals(toAccountId)) {
                throw TransactionException.badRequest("A transfer needs two different accounts.");
            }
        }

        @Override
        public UserTransaction.Type type() {
            return UserTransaction.Type.TRANSFER;
        }

        /** {@code +to −from}. */
        @Override
        public List<Posting> postings() {
            return List.of(new Posting(toAccountId, amount), new Posting(fromAccountId, amount.negate()));
        }
    }

    // The schema asks for an amount above zero too; this keeps the rule true for every caller.
    private static void requirePositive(Money amount) {
        if (amount.minor() <= 0) {
            throw TransactionException.badRequest("The amount must be above zero.");
        }
    }

    // The schema refuses blank descriptions, but its pattern only knows ASCII spaces; strip() knows all.
    private static String clean(String description) {
        String stripped = description.strip();
        if (stripped.isEmpty()) {
            throw TransactionException.badRequest("A transaction needs a description.");
        }
        return stripped;
    }
}
