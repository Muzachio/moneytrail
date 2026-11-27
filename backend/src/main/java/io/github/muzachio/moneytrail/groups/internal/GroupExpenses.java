package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.groups.ExpenseCategory;
import io.github.muzachio.moneytrail.groups.GroupExpenseAdded;
import io.github.muzachio.moneytrail.groups.GroupExpenseEdited;
import io.github.muzachio.moneytrail.groups.GroupExpenseReversed;
import io.github.muzachio.moneytrail.groups.internal.GroupStore.Lock;
import io.github.muzachio.moneytrail.groups.internal.GroupStore.Row;
import io.github.muzachio.moneytrail.groups.internal.Split.MemberShare;
import io.github.muzachio.moneytrail.ledger.EntryFilter;
import io.github.muzachio.moneytrail.ledger.EntryPage;
import io.github.muzachio.moneytrail.ledger.EntryPosition;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.DomainException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The expenses of a group (ADR-0012), and the group's list, where its settlements show next to them.
 * Each version of an expense is one journal entry of the group's ledger: the member who paid {@code
 * +total}, each person in the split {@code −share}. Its split is stored next to it. Like a personal
 * transaction, an expense is never changed: editing reverses its entry and posts the correction, so
 * each edit adds a version to its history. Any member may record, edit or reverse any expense, and
 * each version keeps who made it.
 */
@Service
@Transactional
class GroupExpenses {

    /**
     * The whole calendar, for a list with no period: the Postgres driver sends these two as {@code
     * -infinity} and {@code infinity}, so the ledger's period condition holds for every day.
     */
    private static final LocalDate FIRST_DAY = LocalDate.MIN;

    private static final LocalDate LAST_DAY = LocalDate.MAX;

    private final Groups groups;
    private final GroupStore groupStore;
    private final ExpenseStore store;
    private final SettlementStore settlementStore;
    private final LedgerService ledger;
    private final ApplicationEventPublisher events;

    GroupExpenses(
            Groups groups,
            GroupStore groupStore,
            ExpenseStore store,
            SettlementStore settlementStore,
            LedgerService ledger,
            ApplicationEventPublisher events) {
        this.groups = groups;
        this.groupStore = groupStore;
        this.store = store;
        this.settlementStore = settlementStore;
        this.ledger = ledger;
        this.events = events;
    }

    /**
     * Under {@code Idempotency-Key} this runs in the transaction that stores the response, so a retry
     * finds the entry, its split and the response, or none of them.
     */
    Expense record(UUID userId, UUID groupId, NewExpense expense) {
        Row group = groups.forPosting(userId, groupId);
        Split split = splitOf(expense, group);
        Map<UUID, UUID> accounts = lockMembers(group, people(expense, split), Set.of(), null);
        JournalEntry entry = ledger.post(entryOf(group, expense, split, accounts, userId));
        store.insertSplit(entry.id(), split);
        store.insertCategory(entry.id(), expense.category());
        events.publishEvent(new GroupExpenseAdded(
                group.id(),
                entry.id(),
                entry.description(),
                expense.category(),
                expense.amount(),
                entry.occurredOn(),
                expense.paidBy(),
                userId,
                entry.createdAt()));
        return new Expense(
                entry.id(),
                expense.amount(),
                entry.occurredOn(),
                entry.description(),
                expense.category(),
                expense.paidBy(),
                split,
                userId,
                entry.createdAt(),
                false,
                false);
    }

    @Transactional(readOnly = true)
    ExpenseHistory find(UUID userId, UUID groupId, UUID expenseId) {
        return historyOf(member(userId, groupId), expenseId);
    }

    /**
     * The group's list: the current version of each expense and each settlement, newest first. Every
     * entry in effect in a group's ledger is one of the two: an expense has its split, a settlement
     * its row in {@code settlements}.
     */
    @Transactional(readOnly = true)
    MovementPage list(UUID userId, UUID groupId, @Nullable String cursor, int limit) {
        Row group = member(userId, groupId);
        EntryFilter inEffect = new EntryFilter(group.ledgerId(), FIRST_DAY, LAST_DAY, Set.of(), null, Set.of(), null);
        EntryPage page = ledger.entries(inEffect, cursor == null ? null : ExpenseCursors.decode(cursor), limit);
        List<UUID> ids = page.entries().stream().map(JournalEntry::id).toList();
        Map<UUID, Split> splits = store.splitsOf(ids, group.currency());
        Map<UUID, ExpenseCategory> categories = store.categoriesOf(ids);
        Set<UUID> settlements = settlementStore.settlementsAmong(ids);
        Map<UUID, UUID> owners = groupStore.accountOwners(group.id());
        List<Movement> items = new ArrayList<>();
        for (JournalEntry entry : page.entries()) {
            Split split = splits.get(entry.id());
            if (split != null) {
                items.add(expenseOf(entry, split, categoryOf(categories, entry.id()), owners, false));
            } else if (settlements.contains(entry.id())) {
                items.add(Settlements.settlementOf(entry, owners, false));
            } else {
                throw new IllegalStateException(
                        "Journal entry " + entry.id() + " of a group is neither an expense nor a settlement");
            }
        }
        EntryPosition next = page.next();
        return new MovementPage(items, next == null ? null : ExpenseCursors.encode(next));
    }

    /**
     * Reverses the expense, and records its correction when there is a {@code replacement}, in this
     * one database transaction (and so, under {@code Idempotency-Key}, together with the stored
     * response). An expense that moves the balance of someone who left cannot be reversed: that
     * would change the balance of a person who is not in the group (ADR-0012).
     *
     * @return the correction's history, or the reversed expense's when there is no replacement
     */
    ExpenseHistory reverse(UUID userId, UUID groupId, UUID expenseId, @Nullable NewExpense replacement) {
        Row group = groups.forPosting(userId, groupId);
        ExpenseHistory history = historyOf(group, expenseId);
        Expense reversed = history.expense();
        if (reversed.reversed()) {
            throw GroupExpenseException.alreadyReversed(expenseId);
        }
        Set<UUID> moved = balancesMovedBy(reversed);
        if (replacement == null) {
            lockMembers(group, Set.of(), moved, expenseId);
            JournalEntry reversal = reversing(expenseId, () -> ledger.reverse(expenseId, userId));
            events.publishEvent(new GroupExpenseReversed(
                    group.id(),
                    expenseId,
                    reversed.description(),
                    reversed.category(),
                    reversed.amount(),
                    userId,
                    reversal.createdAt()));
            return historyOf(group, expenseId);
        }
        Split split = splitOf(replacement, group);
        Map<UUID, UUID> accounts = lockMembers(group, people(replacement, split), moved, expenseId);
        JournalEntry correction = reversing(
                expenseId, () -> ledger.replace(expenseId, entryOf(group, replacement, split, accounts, userId)));
        store.insertSplit(correction.id(), split);
        store.insertCategory(correction.id(), replacement.category());
        events.publishEvent(new GroupExpenseEdited(
                group.id(),
                correction.id(),
                expenseId,
                correction.description(),
                replacement.category(),
                replacement.amount(),
                correction.occurredOn(),
                replacement.paidBy(),
                reversed.description(),
                reversed.category(),
                reversed.amount(),
                userId,
                correction.createdAt()));
        return historyOf(group, correction.id());
    }

    private Split splitOf(NewExpense expense, Row group) {
        if (!expense.amount().currency().equals(group.currency())) {
            throw GroupExpenseException.currencyMismatch(
                    expense.amount().currency().getCurrencyCode());
        }
        return Splits.divide(expense.amount(), expense.method(), expense.inputs());
    }

    /** Who paid, then everyone in the split. */
    private static Set<UUID> people(NewExpense expense, Split split) {
        Set<UUID> people = new LinkedHashSet<>();
        people.add(expense.paidBy());
        split.shares().forEach(share -> people.add(share.memberId()));
        return people;
    }

    /** Whose balance the expense changed: who paid, and everyone with a share above zero. */
    private static Set<UUID> balancesMovedBy(Expense expense) {
        Set<UUID> moved = new LinkedHashSet<>();
        moved.add(expense.paidBy());
        expense.split().shares().stream()
                .filter(share -> !share.share().isZero())
                .forEach(share -> moved.add(share.memberId()));
        return moved;
    }

    /**
     * Locks the member rows of everyone the request posts to or reverses, in one query, and returns
     * their accounts. {@code reversing}: people whose balance the reversed expense moved, who must
     * all still be members ({@code group_expense_member_left} otherwise). {@code posting}: people a
     * new version posts to, who must be members ({@code member_not_found}, for someone who never was
     * as for someone who left).
     */
    private Map<UUID, UUID> lockMembers(
            Row group, Set<UUID> posting, Set<UUID> reversing, @Nullable UUID reversedExpenseId) {
        Set<UUID> everyone = new LinkedHashSet<>(reversing);
        everyone.addAll(posting);
        Map<UUID, UUID> accounts = groupStore.lockCurrentMembers(group.id(), everyone);
        if (reversedExpenseId != null && !accounts.keySet().containsAll(reversing)) {
            throw GroupExpenseException.memberLeft(reversedExpenseId);
        }
        posting.stream()
                .filter(userId -> !accounts.containsKey(userId))
                .min((a, b) -> a.toString().compareTo(b.toString()))
                .ifPresent(userId -> {
                    throw GroupExpenseException.memberNotFound(userId);
                });
        return accounts;
    }

    /**
     * {@code +total} for who paid, {@code −share} for everyone in the split, in the split's order. A
     * share of zero posts nothing, since the ledger refuses zero amounts; who paid and has a share
     * gets both postings, kept apart (ADR-0012).
     */
    private static NewJournalEntry entryOf(
            Row group, NewExpense expense, Split split, Map<UUID, UUID> accounts, UUID recordedBy) {
        List<Posting> postings = new ArrayList<>();
        postings.add(new Posting(accountOf(accounts, expense.paidBy()), expense.amount()));
        for (MemberShare share : split.shares()) {
            if (!share.share().isZero()) {
                postings.add(new Posting(
                        accountOf(accounts, share.memberId()), share.share().negate()));
            }
        }
        return new NewJournalEntry(group.ledgerId(), expense.occurredOn(), expense.description(), recordedBy, postings);
    }

    static UUID accountOf(Map<UUID, UUID> accounts, UUID userId) {
        return Objects.requireNonNull(
                accounts.get(userId), () -> "Member " + userId + " was locked without an account");
    }

    /**
     * The check above cannot see a reversal that a concurrent request has not committed yet; the
     * ledger then waits for it and refuses this one, with its own code, which is translated here.
     */
    private static JournalEntry reversing(UUID expenseId, Supplier<JournalEntry> reversal) {
        try {
            return reversal.get();
        } catch (DomainException e) {
            if ("entry_already_reversed".equals(e.code())) {
                throw GroupExpenseException.alreadyReversed(expenseId);
            }
            throw e;
        }
    }

    private Row member(UUID userId, UUID groupId) {
        return groupStore.findForMember(groupId, userId, Lock.NONE).orElseThrow(() -> GroupException.notFound(groupId));
    }

    /**
     * The version {@code expenseId} of an expense of this group, with every version and reversal of
     * it. An id of another ledger, or of an entry that is not an expense, such as a settlement or a
     * reversal, is not found.
     */
    private ExpenseHistory historyOf(Row group, UUID expenseId) {
        List<JournalEntry> entries = ledger.history(expenseId);
        JournalEntry entry = entries.stream()
                .filter(found -> found.id().equals(expenseId)
                        && found.ledgerId().equals(group.ledgerId())
                        && !found.isReversal())
                .findFirst()
                .orElseThrow(() -> GroupExpenseException.notFound(expenseId));
        Split split = store.splitsOf(List.of(expenseId), group.currency()).get(expenseId);
        if (split == null) {
            throw GroupExpenseException.notFound(expenseId);
        }
        Map<UUID, ExpenseCategory> categories =
                store.categoriesOf(entries.stream().map(JournalEntry::id).toList());
        Map<UUID, UUID> owners = groupStore.accountOwners(group.id());
        Expense expense = expenseOf(
                entry,
                split,
                categoryOf(categories, expenseId),
                owners,
                Trails.reversed(entries).contains(expenseId));
        return new ExpenseHistory(expense, Trails.of(entries, categories), Trails.current(entries));
    }

    /** Every expense has one: V18 gave {@code other} to those recorded before categories existed. */
    private static ExpenseCategory categoryOf(Map<UUID, ExpenseCategory> categories, UUID expenseId) {
        return Objects.requireNonNull(categories.get(expenseId), () -> "Expense " + expenseId + " has no category");
    }

    /** Reads the entry back: the one posting above zero is who paid, and its amount the total. */
    private static Expense expenseOf(
            JournalEntry entry, Split split, ExpenseCategory category, Map<UUID, UUID> owners, boolean reversed) {
        Posting paid = Trails.paid(entry);
        UUID paidBy = Objects.requireNonNull(
                owners.get(paid.accountId()), () -> "Account " + paid.accountId() + " is no member's");
        return new Expense(
                entry.id(),
                paid.amount(),
                entry.occurredOn(),
                entry.description(),
                category,
                paidBy,
                split,
                entry.createdBy(),
                entry.createdAt(),
                entry.replacesEntryId() != null,
                reversed);
    }
}
