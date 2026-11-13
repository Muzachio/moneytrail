package io.github.muzachio.moneytrail.personal.internal;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.DomainException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user's expenses, income and transfers, recorded as journal entries of their personal ledger.
 * A transaction is never changed: editing it reverses its entry and records the correction (see
 * {@link LedgerService#replace}), so each edit adds a version to its history.
 */
@Service
@Transactional
class Transactions {

    private final LedgerService ledger;
    private final PersonalLedgers personalLedgers;

    Transactions(LedgerService ledger, PersonalLedgers personalLedgers) {
        this.ledger = ledger;
        this.personalLedgers = personalLedgers;
    }

    /**
     * Under {@code Idempotency-Key} this runs in the transaction that stores the response, so a retry
     * finds both the entry and the response, or neither.
     */
    UserTransaction record(UUID userId, Movement movement) {
        UUID ledgerId = personalLedgers.ledgerOf(userId);
        Map<UUID, Account> accounts = accountsOf(ledgerId);
        checkUsable(movement, accounts, Set.of());
        JournalEntry entry = ledger.post(movement.toEntry(ledgerId, userId));
        return UserTransaction.of(entry, accounts, false).orElseThrow(() -> notATransaction(entry));
    }

    // Not read-only: ledgerOf opens the ledger of a user who has none yet, as the other endpoints do.
    TransactionHistory find(UUID userId, UUID transactionId) {
        UUID ledgerId = personalLedgers.ledgerOf(userId);
        Map<UUID, Account> accounts = accountsOf(ledgerId);
        return historyOf(ledgerId, transactionId, accounts);
    }

    /**
     * Reverses the transaction, and records its correction when there is a {@code replacement}, in
     * this one database transaction (and so, under {@code Idempotency-Key}, together with the stored
     * response).
     *
     * <p>The reversal may post to an archived account or category: it only takes back what the
     * transaction recorded, and refusing it would leave a mistake in the history until the account
     * was restored. The replacement is a new movement, so it may not use an archived account or
     * category, except one that the transaction it corrects already used: correcting the amount of an
     * old card payment should not need the closed card restored first.
     *
     * @return the correction's history, or the reversed transaction's when there is no replacement
     */
    TransactionHistory reverse(UUID userId, UUID transactionId, @Nullable Movement replacement) {
        UUID ledgerId = personalLedgers.ledgerOf(userId);
        Map<UUID, Account> accounts = accountsOf(ledgerId);
        TransactionHistory history = historyOf(ledgerId, transactionId, accounts);
        if (history.transaction().reversed()) {
            throw TransactionException.alreadyReversed(transactionId);
        }
        if (replacement == null) {
            reversing(transactionId, () -> ledger.reverse(transactionId, userId));
            return historyOf(ledgerId, transactionId, accounts);
        }
        Set<UUID> alreadyUsed =
                history.postings().stream().map(Posting::accountId).collect(toSet());
        checkUsable(replacement, accounts, alreadyUsed);
        JournalEntry correction =
                reversing(transactionId, () -> ledger.replace(transactionId, replacement.toEntry(ledgerId, userId)));
        return historyOf(ledgerId, correction.id(), accounts);
    }

    /**
     * The check above cannot see a reversal that a concurrent request has not committed yet; the
     * ledger then waits for it and refuses this one, with its own code, which is translated here.
     */
    private static JournalEntry reversing(UUID transactionId, Supplier<JournalEntry> reversal) {
        try {
            return reversal.get();
        } catch (DomainException e) {
            if ("entry_already_reversed".equals(e.code())) {
                throw TransactionException.alreadyReversed(transactionId);
            }
            throw e;
        }
    }

    private TransactionHistory historyOf(UUID ledgerId, UUID transactionId, Map<UUID, Account> accounts) {
        List<JournalEntry> entries = ledger.history(transactionId);
        Set<UUID> reversedIds = entries.stream()
                .map(JournalEntry::reversesEntryId)
                .filter(Objects::nonNull)
                .collect(toSet());
        JournalEntry entry = entries.stream()
                .filter(found ->
                        found.id().equals(transactionId) && found.ledgerId().equals(ledgerId))
                .findFirst()
                .orElseThrow(() -> TransactionException.notFound(transactionId));
        UserTransaction transaction = UserTransaction.of(entry, accounts, reversedIds.contains(transactionId))
                .orElseThrow(() -> TransactionException.notFound(transactionId));

        Map<UUID, TransactionHistory.Step> steps = new HashMap<>();
        List<TransactionHistory.Step> trail = new ArrayList<>();
        for (JournalEntry version : entries) {
            TransactionHistory.Step step = stepOf(version, accounts, reversedIds, steps);
            steps.put(version.id(), step);
            trail.add(step);
        }
        JournalEntry last = entries.getLast();
        return new TransactionHistory(transaction, entry.postings(), trail, last.isReversal() ? null : last.id());
    }

    private static TransactionHistory.Step stepOf(
            JournalEntry entry,
            Map<UUID, Account> accounts,
            Set<UUID> reversedIds,
            Map<UUID, TransactionHistory.Step> earlierSteps) {
        UUID reversed = entry.reversesEntryId();
        if (reversed != null) {
            TransactionHistory.Step cancelled = Objects.requireNonNull(
                    earlierSteps.get(reversed), () -> "The history lists " + reversed + " after its reversal");
            return new TransactionHistory.Step(
                    entry.id(),
                    TransactionHistory.Action.REVERSED,
                    cancelled.type(),
                    cancelled.amount(),
                    entry.createdAt(),
                    reversed);
        }
        UserTransaction version = UserTransaction.of(entry, accounts, reversedIds.contains(entry.id()))
                .orElseThrow(() -> notATransaction(entry));
        return new TransactionHistory.Step(
                entry.id(),
                version.edited() ? TransactionHistory.Action.CORRECTED : TransactionHistory.Action.RECORDED,
                version.type(),
                version.amount(),
                entry.createdAt(),
                null);
    }

    /**
     * Every account and category the movement names must be the user's, of the right kind, active
     * (unless {@code mayBeArchived} holds it) and in the amount's currency. Ids from another ledger
     * are not in {@code accounts}, so they are not found, without revealing whether they exist.
     */
    private static void checkUsable(Movement movement, Map<UUID, Account> accounts, Set<UUID> mayBeArchived) {
        switch (movement) {
            case Movement.Expense expense -> {
                checkCategory(expense.categoryId(), AccountKind.EXPENSE, movement, accounts, mayBeArchived);
                checkAccount(expense.accountId(), movement, accounts, mayBeArchived);
            }
            case Movement.Income income -> {
                checkCategory(income.categoryId(), AccountKind.INCOME, movement, accounts, mayBeArchived);
                checkAccount(income.accountId(), movement, accounts, mayBeArchived);
            }
            case Movement.Transfer transfer -> {
                checkAccount(transfer.fromAccountId(), movement, accounts, mayBeArchived);
                checkAccount(transfer.toAccountId(), movement, accounts, mayBeArchived);
            }
        }
    }

    private static void checkCategory(
            UUID categoryId,
            AccountKind kind,
            Movement movement,
            Map<UUID, Account> accounts,
            Set<UUID> mayBeArchived) {
        Account category = accounts.get(categoryId);
        if (category == null || (category.kind() != AccountKind.EXPENSE && category.kind() != AccountKind.INCOME)) {
            throw TransactionException.categoryNotFound(categoryId);
        }
        if (category.kind() != kind) {
            throw TransactionException.categoryKindMismatch(categoryId, movement.type());
        }
        if (category.archived() && !mayBeArchived.contains(categoryId)) {
            throw TransactionException.categoryArchived(categoryId);
        }
        checkCurrency(category, movement);
    }

    private static void checkAccount(
            UUID accountId, Movement movement, Map<UUID, Account> accounts, Set<UUID> mayBeArchived) {
        Account account = accounts.get(accountId);
        if (account == null || UserAccount.Type.of(account).isEmpty()) {
            throw TransactionException.accountNotFound(accountId);
        }
        if (account.archived() && !mayBeArchived.contains(accountId)) {
            throw TransactionException.accountArchived(accountId);
        }
        checkCurrency(account, movement);
    }

    // The ledger checks this too, but answers with its own code; here the user learns which rule.
    private static void checkCurrency(Account account, Movement movement) {
        if (!account.currency().equals(movement.amount().currency())) {
            throw TransactionException.currencyMismatch(
                    movement.amount().currency().getCurrencyCode());
        }
    }

    private Map<UUID, Account> accountsOf(UUID ledgerId) {
        return ledger.accounts(ledgerId).stream().collect(toMap(Account::id, Function.identity()));
    }

    private static IllegalStateException notATransaction(JournalEntry entry) {
        return new IllegalStateException("Journal entry " + entry.id() + " does not read back as a transaction");
    }
}
