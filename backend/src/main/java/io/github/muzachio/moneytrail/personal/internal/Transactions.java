package io.github.muzachio.moneytrail.personal.internal;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.EntryFilter;
import io.github.muzachio.moneytrail.ledger.EntryPage;
import io.github.muzachio.moneytrail.ledger.EntryPosition;
import io.github.muzachio.moneytrail.ledger.EntryTotals;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.personal.internal.GroupMirrorStore.Handled;
import io.github.muzachio.moneytrail.shared.DomainException;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    private final GroupMirrorStore mirrors;

    Transactions(LedgerService ledger, PersonalLedgers personalLedgers, GroupMirrorStore mirrors) {
        this.ledger = ledger;
        this.personalLedgers = personalLedgers;
        this.mirrors = mirrors;
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
        return historyOf(userId, ledgerId, transactionId, accounts);
    }

    /**
     * The current version of each transaction in the month, newest first, and the month's totals for
     * the same filters. The account and the category of a filter must be the user's.
     */
    TransactionPage list(UUID userId, TransactionFilter filter, @Nullable String cursor, int limit) {
        UUID ledgerId = personalLedgers.ledgerOf(userId);
        Map<UUID, Account> accounts = accountsOf(ledgerId);
        EntryFilter entries = entryFilter(ledgerId, filter, accounts);
        EntryPage page = ledger.entries(entries, cursor == null ? null : Cursors.decode(cursor), limit);
        List<UserTransaction> items = withGroups(
                userId,
                page.entries().stream()
                        .map(entry ->
                                UserTransaction.of(entry, accounts, false).orElseThrow(() -> notATransaction(entry)))
                        .toList());
        EntryPosition next = page.next();
        return new TransactionPage(
                filter.month(), summaryOf(ledger.totals(entries)), items, next == null ? null : Cursors.encode(next));
    }

    /**
     * Opening balances post to {@code EQUITY} and are never transactions. A transfer only moves money
     * between the user's own accounts, so it is an entry that posts to no category.
     */
    private static EntryFilter entryFilter(UUID ledgerId, TransactionFilter filter, Map<UUID, Account> accounts) {
        Set<UUID> postingTo = new HashSet<>();
        UUID accountId = filter.accountId();
        if (accountId != null) {
            Account account = accounts.get(accountId);
            if (account == null || UserAccount.Type.of(account).isEmpty()) {
                throw TransactionException.accountNotFound(accountId);
            }
            postingTo.add(accountId);
        }
        UUID categoryId = filter.categoryId();
        if (categoryId != null) {
            Account category = accounts.get(categoryId);
            if (category == null || !isCategory(category)) {
                throw TransactionException.categoryNotFound(categoryId);
            }
            postingTo.add(categoryId);
        }
        AccountKind postingToKind = null;
        Set<AccountKind> notPostingToKinds = EnumSet.of(AccountKind.EQUITY);
        UserTransaction.Type type = filter.type();
        if (type != null) {
            switch (type) {
                case EXPENSE -> postingToKind = AccountKind.EXPENSE;
                case INCOME -> postingToKind = AccountKind.INCOME;
                case TRANSFER -> notPostingToKinds.addAll(EnumSet.of(AccountKind.EXPENSE, AccountKind.INCOME));
            }
        }
        YearMonth month = filter.month();
        return new EntryFilter(
                ledgerId,
                month.atDay(1),
                month.atEndOfMonth(),
                postingTo,
                postingToKind,
                notPostingToKinds,
                filter.text());
    }

    /** Spending is what the expense categories were debited, income what the income ones were credited. */
    private static TransactionPage.Summary summaryOf(EntryTotals totals) {
        Money zero = Money.zero(PersonalLedgers.CURRENCY);
        Money spending = totals.sums().getOrDefault(AccountKind.EXPENSE, zero);
        Money income = totals.sums().getOrDefault(AccountKind.INCOME, zero).negate();
        return new TransactionPage.Summary(totals.count(), income, spending, income.minus(spending));
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
        TransactionHistory history = historyOf(userId, ledgerId, transactionId, accounts);
        if (history.transaction().group() != null) {
            throw TransactionException.transactionFromGroup(transactionId);
        }
        if (history.transaction().reversed()) {
            throw TransactionException.alreadyReversed(transactionId);
        }
        if (replacement == null) {
            reversing(transactionId, () -> ledger.reverse(transactionId, userId));
            return historyOf(userId, ledgerId, transactionId, accounts);
        }
        Set<UUID> alreadyUsed =
                history.postings().stream().map(Posting::accountId).collect(toSet());
        checkUsable(replacement, accounts, alreadyUsed);
        JournalEntry correction =
                reversing(transactionId, () -> ledger.replace(transactionId, replacement.toEntry(ledgerId, userId)));
        return historyOf(userId, ledgerId, correction.id(), accounts);
    }

    /**
     * Moves what a mirrored transaction took from, or brought to, one of the user's accounts onto
     * another (ADR-0015): the entry is replaced with the same postings on the new account, as an edit
     * is. It takes the mirror's lock first, so the listener never corrects the same entry meanwhile;
     * the history is read after the lock, when nothing can change it any more.
     */
    TransactionHistory changeAccount(UUID userId, UUID transactionId, UUID accountId) {
        UUID ledgerId = personalLedgers.ledgerOf(userId);
        Map<UUID, Account> accounts = accountsOf(ledgerId);
        UserTransaction.GroupLink link = historyOf(userId, ledgerId, transactionId, accounts)
                .transaction()
                .group();
        // The new account first, as for a new transaction: whether it is usable does not depend on
        // which transaction it is for.
        checkAccount(accountId, PersonalLedgers.CURRENCY, accounts, Set.of());
        if (link == null) {
            throw TransactionException.accountChangeRefused(transactionId);
        }
        mirrors.lock(userId, link.groupId());
        TransactionHistory history = historyOf(userId, ledgerId, transactionId, accounts);
        if (history.transaction().reversed()) {
            throw TransactionException.alreadyReversed(transactionId);
        }
        Account current = history.postings().stream()
                .map(Posting::accountId)
                .filter(id -> !id.equals(link.groupAccountId()))
                .map(accounts::get)
                .filter(Objects::nonNull)
                .filter(account -> UserAccount.Type.of(account).isPresent())
                .findFirst()
                .orElseThrow(() -> TransactionException.accountChangeRefused(transactionId));
        if (accountId.equals(current.id())) {
            return history;
        }
        UserTransaction transaction = history.transaction();
        List<Posting> postings = history.postings().stream()
                .map(posting ->
                        posting.accountId().equals(current.id()) ? new Posting(accountId, posting.amount()) : posting)
                .toList();
        JournalEntry corrected = reversing(
                transactionId,
                () -> ledger.replace(
                        transactionId,
                        new NewJournalEntry(
                                ledgerId, transaction.occurredOn(), transaction.description(), userId, postings)));
        mirrors.recordedAs(link.groupEntryId(), userId, corrected.id());
        return historyOf(userId, ledgerId, corrected.id(), accounts);
    }

    /** Adds to each transaction that the mirror of a group recorded which group entry it records. */
    private List<UserTransaction> withGroups(UUID userId, List<UserTransaction> transactions) {
        Map<UUID, Handled> handled = mirrors.byPersonalEntries(
                transactions.stream().map(UserTransaction::id).toList());
        if (handled.isEmpty()) {
            return transactions;
        }
        Map<UUID, UUID> groupAccounts = groupAccountsOf(userId);
        return transactions.stream()
                .map(transaction -> {
                    Handled recorded = handled.get(transaction.id());
                    return recorded == null ? transaction : transaction.withGroup(link(recorded, groupAccounts));
                })
                .toList();
    }

    private Map<UUID, UUID> groupAccountsOf(UUID userId) {
        return mirrors.groupsByAccount(userId).entrySet().stream()
                .collect(toMap(Map.Entry::getValue, Map.Entry::getKey));
    }

    private static UserTransaction.GroupLink link(Handled recorded, Map<UUID, UUID> groupAccounts) {
        return new UserTransaction.GroupLink(
                recorded.groupId(),
                Objects.requireNonNull(groupAccounts.get(recorded.groupId())),
                recorded.groupEntryId(),
                Objects.requireNonNull(recorded.type(), "A recorded group entry has a type"));
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

    /**
     * A version of a mirrored transaction that an account change replaced has no row of its own any
     * more: the row follows the version in effect. Every version of one history records the same group
     * entry, so the link of any of them is the link of all.
     */
    private TransactionHistory historyOf(UUID userId, UUID ledgerId, UUID transactionId, Map<UUID, Account> accounts) {
        TransactionHistory history = historyOf(ledgerId, transactionId, accounts);
        Map<UUID, Handled> handled = mirrors.byPersonalEntries(
                history.trail().stream().map(TransactionHistory.Step::entryId).toList());
        if (handled.isEmpty()) {
            return history;
        }
        Handled recorded =
                handled.getOrDefault(transactionId, handled.values().iterator().next());
        return new TransactionHistory(
                history.transaction().withGroup(link(recorded, groupAccountsOf(userId))),
                history.postings(),
                history.trail(),
                history.currentId());
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
                checkAccount(expense.accountId(), movement.amount().currency(), accounts, mayBeArchived);
            }
            case Movement.Income income -> {
                checkCategory(income.categoryId(), AccountKind.INCOME, movement, accounts, mayBeArchived);
                checkAccount(income.accountId(), movement.amount().currency(), accounts, mayBeArchived);
            }
            case Movement.Transfer transfer -> {
                checkAccount(transfer.fromAccountId(), movement.amount().currency(), accounts, mayBeArchived);
                checkAccount(transfer.toAccountId(), movement.amount().currency(), accounts, mayBeArchived);
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
        if (category == null || !isCategory(category)) {
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

    /** A group's account only holds what the mirror records, so none of the user's movements uses it. */
    private static void checkAccount(
            UUID accountId, Currency currency, Map<UUID, Account> accounts, Set<UUID> mayBeArchived) {
        Account account = accounts.get(accountId);
        Optional<UserAccount.Type> type = account == null ? Optional.empty() : UserAccount.Type.of(account);
        if (account == null || type.isEmpty()) {
            throw TransactionException.accountNotFound(accountId);
        }
        if (type.get() == UserAccount.Type.GROUP) {
            throw TransactionException.accountFromGroup(accountId);
        }
        if (account.archived() && !mayBeArchived.contains(accountId)) {
            throw TransactionException.accountArchived(accountId);
        }
        checkCurrency(account, currency);
    }

    private static void checkCurrency(Account account, Movement movement) {
        checkCurrency(account, movement.amount().currency());
    }

    // The ledger checks this too, but answers with its own code; here the user learns which rule.
    private static void checkCurrency(Account account, Currency currency) {
        if (!account.currency().equals(currency)) {
            throw TransactionException.currencyMismatch(currency.getCurrencyCode());
        }
    }

    private static boolean isCategory(Account account) {
        return account.kind() == AccountKind.EXPENSE || account.kind() == AccountKind.INCOME;
    }

    private Map<UUID, Account> accountsOf(UUID ledgerId) {
        return ledger.accounts(ledgerId).stream().collect(toMap(Account::id, Function.identity()));
    }

    private static IllegalStateException notATransaction(JournalEntry entry) {
        return new IllegalStateException("Journal entry " + entry.id() + " does not read back as a transaction");
    }
}
