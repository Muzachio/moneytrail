package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One version of a transaction as the API shows it: a journal entry of the user's ledger read back
 * as the {@link Movement} it was recorded from. Expenses and income have a category and an account,
 * transfers a from and a to account.
 *
 * @param edited this version corrects an earlier one
 * @param reversed a later entry cancelled this version
 */
record UserTransaction(
        UUID id,
        Type type,
        Money amount,
        LocalDate occurredOn,
        String description,
        @Nullable UUID categoryId,
        @Nullable UUID accountId,
        @Nullable UUID fromAccountId,
        @Nullable UUID toAccountId,
        Instant recordedAt,
        boolean edited,
        boolean reversed) {

    enum Type {
        EXPENSE,
        INCOME,
        TRANSFER
    }

    /**
     * Reads the entry by the kinds of the accounts it posts to, the way {@link Movement} wrote it. An
     * entry that is not a transaction gives none: a reversal (its signs are the other way round), an
     * account's opening balance (it posts to equity), or any entry of another shape.
     *
     * @param accounts the accounts of the entry's ledger, by id
     */
    static Optional<UserTransaction> of(JournalEntry entry, Map<UUID, Account> accounts, boolean reversed) {
        List<Posting> postings = entry.postings();
        if (entry.isReversal() || postings.size() != 2) {
            return Optional.empty();
        }
        // A balanced entry of two postings, neither of them zero, has one debit and one credit.
        int debitAt = postings.get(0).amount().minor() > 0 ? 0 : 1;
        Posting debit = postings.get(debitAt);
        Posting credit = postings.get(1 - debitAt);
        Account debited = accounts.get(debit.accountId());
        Account credited = accounts.get(credit.accountId());
        if (debited == null || credited == null) {
            return Optional.empty();
        }
        Builder builder = new Builder(entry, debit.amount(), reversed);
        if (debited.kind() == AccountKind.EXPENSE && isUserAccount(credited)) {
            return Optional.of(builder.build(Type.EXPENSE, debited.id(), credited.id(), null, null));
        }
        if (credited.kind() == AccountKind.INCOME && isUserAccount(debited)) {
            return Optional.of(builder.build(Type.INCOME, credited.id(), debited.id(), null, null));
        }
        if (isUserAccount(debited) && isUserAccount(credited)) {
            return Optional.of(builder.build(Type.TRANSFER, null, null, credited.id(), debited.id()));
        }
        return Optional.empty();
    }

    private static boolean isUserAccount(Account account) {
        return UserAccount.Type.of(account).isPresent();
    }

    private record Builder(JournalEntry entry, Money amount, boolean reversed) {

        UserTransaction build(
                Type type,
                @Nullable UUID categoryId,
                @Nullable UUID accountId,
                @Nullable UUID fromAccountId,
                @Nullable UUID toAccountId) {
            return new UserTransaction(
                    entry.id(),
                    type,
                    amount,
                    entry.occurredOn(),
                    entry.description(),
                    categoryId,
                    accountId,
                    fromAccountId,
                    toAccountId,
                    entry.createdAt(),
                    entry.replacesEntryId() != null,
                    reversed);
        }
    }
}
