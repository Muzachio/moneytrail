package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.personal.internal.GroupMirrorStore.EntryType;
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
 * <p>An entry that the mirror of a group recorded (ADR-0015) reads back the same way, with {@code
 * group} saying which group entry it records. The one shape no {@link Movement} has is the share of
 * an expense the user paid for others too: their share to the category, what they paid from the
 * account, and the rest to the group's account. It reads back as an expense of the share, with
 * {@code paid}.
 *
 * @param paid for an expense the user paid and shares: the whole amount that left {@code accountId}
 * @param edited this version corrects an earlier one
 * @param reversed a later entry cancelled this version
 * @param group the group entry this records, when the mirror of a group recorded it
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
        @Nullable Money paid,
        Instant recordedAt,
        boolean edited,
        boolean reversed,
        @Nullable GroupLink group) {

    enum Type {
        EXPENSE,
        INCOME,
        TRANSFER
    }

    /** Which group entry a mirrored transaction records, and the group's account in the user's ledger. */
    record GroupLink(UUID groupId, UUID groupAccountId, UUID groupEntryId, EntryType type) {}

    UserTransaction withGroup(GroupLink link) {
        // A share the user paid in full has no third posting, so its paid amount is its amount.
        Money paidAmount =
                paid == null && type == Type.EXPENSE && !link.groupAccountId().equals(accountId) ? amount : paid;
        return new UserTransaction(
                id,
                type,
                amount,
                occurredOn,
                description,
                categoryId,
                accountId,
                fromAccountId,
                toAccountId,
                paidAmount,
                recordedAt,
                edited,
                reversed,
                link);
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
        if (entry.isReversal()) {
            return Optional.empty();
        }
        if (postings.size() == 3) {
            return shareOfAPaidExpense(entry, accounts, reversed);
        }
        if (postings.size() != 2) {
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
        Builder builder = new Builder(entry, debit.amount(), null, reversed);
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

    /** {@code +share} to a category, {@code −paid} from one of the user's accounts, the rest to a group's. */
    private static Optional<UserTransaction> shareOfAPaidExpense(
            JournalEntry entry, Map<UUID, Account> accounts, boolean reversed) {
        Posting category = null;
        Posting payment = null;
        Posting group = null;
        for (Posting posting : entry.postings()) {
            Account account = accounts.get(posting.accountId());
            if (account == null) {
                return Optional.empty();
            }
            boolean debit = posting.amount().minor() > 0;
            Optional<UserAccount.Type> type = UserAccount.Type.of(account);
            if (debit && account.kind() == AccountKind.EXPENSE && category == null) {
                category = posting;
            } else if (debit && type.equals(Optional.of(UserAccount.Type.GROUP)) && group == null) {
                group = posting;
            } else if (!debit && type.isPresent() && payment == null) {
                payment = posting;
            } else {
                return Optional.empty();
            }
        }
        if (category == null || payment == null || group == null) {
            return Optional.empty();
        }
        Builder builder = new Builder(entry, category.amount(), payment.amount().negate(), reversed);
        return Optional.of(builder.build(Type.EXPENSE, category.accountId(), payment.accountId(), null, null));
    }

    private static boolean isUserAccount(Account account) {
        return UserAccount.Type.of(account).isPresent();
    }

    private record Builder(
            JournalEntry entry, Money amount, @Nullable Money paid, boolean reversed) {

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
                    paid,
                    entry.createdAt(),
                    entry.replacesEntryId() != null,
                    reversed,
                    null);
        }
    }
}
