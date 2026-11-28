package io.github.muzachio.moneytrail.personal.internal;

import static java.util.stream.Collectors.toMap;

import io.github.muzachio.moneytrail.groups.ExpenseCategory;
import io.github.muzachio.moneytrail.groups.GroupMembership;
import io.github.muzachio.moneytrail.groups.GroupMembership.MemberAccount;
import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.AccountSnapshot;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.personal.internal.GroupMirrorStore.EntryType;
import io.github.muzachio.moneytrail.personal.internal.GroupMirrorStore.Handled;
import io.github.muzachio.moneytrail.personal.internal.GroupMirrorStore.Mirror;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A member's share of a group recorded in their personal ledger, when they turn it on for that group
 * (ADR-0015). The group's entries stay the source of the amounts: for each one, the member's postings
 * in it say what they paid and what their share was ({@link GroupShare}).
 */
@Service
@Transactional
class GroupMirrors {

    private final LedgerService ledger;
    private final PersonalLedgers personalLedgers;
    private final GroupMembership groups;
    private final GroupMirrorStore store;
    private final CategoryKeys keys;

    GroupMirrors(
            LedgerService ledger,
            PersonalLedgers personalLedgers,
            GroupMembership groups,
            GroupMirrorStore store,
            CategoryKeys keys) {
        this.ledger = ledger;
        this.personalLedgers = personalLedgers;
        this.groups = groups;
        this.store = store;
        this.keys = keys;
    }

    /** The member's mirror of the group when it is on. */
    @Transactional(readOnly = true)
    Optional<Mirror> find(UUID userId, UUID groupId) {
        groups.requireMember(groupId, userId);
        return store.find(userId, groupId).filter(Mirror::enabled);
    }

    /**
     * Turns the mirror on, or changes its category and account when it is on. Turning it on opens the
     * group's account in the member's ledger, or restores the one an earlier period opened, and gives it
     * the member's balance in the group against {@code Equity:Opening balances}.
     *
     * <p>The ledger's snapshot keeps any expense from being posted to the member's account in the group
     * until this transaction commits, so every group entry is either in the opening balance, and marked
     * so here, or committed later, when the listener finds the mirror on and records it.
     */
    Mirror turnOn(UUID userId, UUID groupId, UUID categoryId, UUID paymentAccountId) {
        MemberAccount member = groups.memberAccount(groupId, userId);
        UUID ledgerId = personalLedgers.ledgerOf(userId);
        Map<UUID, Account> accounts = accountsOf(ledgerId);
        checkCategory(categoryId, accounts);
        checkPaymentAccount(paymentAccountId, accounts);
        if (!member.currency().equals(PersonalLedgers.CURRENCY)) {
            throw MirrorException.currencyMismatch();
        }
        Optional<Mirror> existing = store.lock(userId, groupId);
        if (existing.isPresent() && existing.get().enabled()) {
            Mirror changed = existing.get().with(categoryId, paymentAccountId, true);
            store.save(changed);
            return changed;
        }
        UUID groupAccountId = existing.map(Mirror::accountId)
                .map(ledger::restoreAccount)
                .orElseGet(() -> ledger.openAccount(new NewAccount(
                        ledgerId,
                        UserAccount.Type.GROUP.kind(),
                        UserAccount.Type.GROUP.key(),
                        member.groupName(),
                        PersonalLedgers.CURRENCY)))
                .id();
        Mirror mirror =
                new Mirror(userId, groupId, member.accountId(), groupAccountId, categoryId, paymentAccountId, true);
        store.save(mirror);
        AccountSnapshot snapshot = ledger.snapshot(member.accountId());
        store.markBeforeMirror(mirror, snapshot.entryIds());
        adjust(mirror, snapshot.balance().minus(ledger.balance(groupAccountId)), ledgerId);
        return mirror;
    }

    /**
     * Turns the mirror off: the group's account gives its balance back to {@code Equity:Opening
     * balances} and is archived. What the mirror recorded stays.
     */
    void turnOff(UUID userId, UUID groupId) {
        groups.requireMember(groupId, userId);
        store.lock(userId, groupId).filter(Mirror::enabled).ifPresent(this::close);
    }

    /** Leaving needs a balance of zero in the group, so this closes an account at zero, or nearly. */
    void left(UUID groupId, UUID userId) {
        store.lock(userId, groupId).filter(Mirror::enabled).ifPresent(this::close);
    }

    /** A new expense or settlement: recorded for each member whose mirror is on and who took part. */
    void recorded(
            UUID groupId, UUID groupEntryId, EntryType type, @Nullable ExpenseCategory category, String description) {
        for (Mirror mirror : store.lockEnabledIn(groupId)) {
            if (store.claim(groupEntryId, mirror, type)) {
                JournalEntry entry = groupEntry(groupEntryId);
                post(mirror, type, entry, category, description, mirror.paymentAccountId())
                        .ifPresent(personal -> store.recordedAs(groupEntryId, mirror.userId(), personal.id()));
            }
        }
    }

    /**
     * An edit: {@code replacedId} was reversed and {@code replacementId} recorded in its place. The
     * personal entry follows: replaced in one database transaction, keeping the account the member
     * chose for it.
     */
    void corrected(
            UUID groupId,
            UUID replacedId,
            UUID replacementId,
            EntryType type,
            @Nullable ExpenseCategory category,
            String description) {
        List<Mirror> enabled = store.lockEnabledIn(groupId);
        if (enabled.isEmpty()) {
            return;
        }
        UUID reversalId = reversalOf(replacedId);
        for (Mirror mirror : enabled) {
            // An edit is two changes, each claimed on its own entry: the replaced version's reversal,
            // and the replacement. A reversal of the replacement can arrive first and take the
            // replacement's row; the replaced version must still be undone then.
            boolean undo = store.claim(reversalId, mirror, type);
            boolean redo = store.claim(replacementId, mirror, type);
            if (!undo && !redo) {
                continue;
            }
            JournalEntry replacement = groupEntry(replacementId);
            Optional<Handled> original = store.handled(replacedId, mirror.userId());
            if (original.isEmpty()) {
                // The edit arrived before the expense it corrects: marking that one handled keeps it
                // from being recorded when it arrives, so only the replacement counts.
                store.claim(replacedId, mirror, type);
                if (redo) {
                    record(mirror, type, replacement, category, description, mirror.paymentAccountId());
                }
            } else if (original.get().beforeMirror()) {
                Money zero = Money.zero(PersonalLedgers.CURRENCY);
                Money change = zero;
                if (undo) {
                    store.markBeforeMirror(reversalId, mirror.userId());
                    change = change.minus(GroupShare.of(groupEntry(replacedId), mirror.memberAccountId(), zero)
                            .change());
                }
                if (redo) {
                    store.markBeforeMirror(replacementId, mirror.userId());
                    change = change.plus(GroupShare.of(replacement, mirror.memberAccountId(), zero)
                            .change());
                }
                adjust(mirror, change, personalLedgers.ledgerOf(mirror.userId()));
            } else if (undo && redo) {
                replace(mirror, original.get(), type, replacement, category, description);
            } else if (undo) {
                undo(mirror, original.get());
            } else {
                record(mirror, type, replacement, category, description, mirror.paymentAccountId());
            }
        }
    }

    /** A reversal without a correction: the personal entry is reversed too. */
    void reversed(UUID groupId, UUID reversedId, EntryType type) {
        // A group nobody mirrors costs one query, and never reads the group's ledger.
        List<Mirror> enabled = store.lockEnabledIn(groupId);
        if (enabled.isEmpty()) {
            return;
        }
        UUID reversalId = reversalOf(reversedId);
        for (Mirror mirror : enabled) {
            if (!store.claim(reversalId, mirror, type)) {
                continue;
            }
            Optional<Handled> original = store.handled(reversedId, mirror.userId());
            if (original.isEmpty()) {
                // The reversal arrived first: the expense, when it arrives, adds nothing.
                store.claim(reversedId, mirror, type);
            } else if (original.get().beforeMirror()) {
                store.markBeforeMirror(reversalId, mirror.userId());
                Money was = GroupShare.of(
                                groupEntry(reversedId), mirror.memberAccountId(), Money.zero(PersonalLedgers.CURRENCY))
                        .change();
                adjust(mirror, was.negate(), personalLedgers.ledgerOf(mirror.userId()));
            } else {
                undo(mirror, original.get());
            }
        }
    }

    private void undo(Mirror mirror, Handled original) {
        UUID personalEntryId = original.personalEntryId();
        if (personalEntryId != null) {
            ledger.reverse(personalEntryId, mirror.userId());
        }
    }

    private void replace(
            Mirror mirror,
            Handled original,
            EntryType type,
            JournalEntry replacement,
            @Nullable ExpenseCategory category,
            String description) {
        UUID personalEntryId = original.personalEntryId();
        if (personalEntryId == null) {
            record(mirror, type, replacement, category, description, mirror.paymentAccountId());
            return;
        }
        UUID ledgerId = personalLedgers.ledgerOf(mirror.userId());
        UUID paymentAccountId = paymentAccountOf(
                        ledger.findEntry(personalEntryId).orElseThrow(), mirror, ledgerId)
                .orElse(mirror.paymentAccountId());
        Optional<NewJournalEntry> next =
                personalEntry(mirror, type, replacement, category, description, paymentAccountId, ledgerId);
        if (next.isEmpty()) {
            ledger.reverse(personalEntryId, mirror.userId());
            return;
        }
        JournalEntry corrected = ledger.replace(personalEntryId, next.get());
        store.recordedAs(replacement.id(), mirror.userId(), corrected.id());
    }

    private void record(
            Mirror mirror,
            EntryType type,
            JournalEntry groupEntry,
            @Nullable ExpenseCategory category,
            String description,
            UUID paymentAccountId) {
        post(mirror, type, groupEntry, category, description, paymentAccountId)
                .ifPresent(personal -> store.recordedAs(groupEntry.id(), mirror.userId(), personal.id()));
    }

    private Optional<JournalEntry> post(
            Mirror mirror,
            EntryType type,
            JournalEntry groupEntry,
            @Nullable ExpenseCategory category,
            String description,
            UUID paymentAccountId) {
        UUID ledgerId = personalLedgers.ledgerOf(mirror.userId());
        return personalEntry(mirror, type, groupEntry, category, description, paymentAccountId, ledgerId)
                .map(ledger::post);
    }

    /** Empty when the member has no posting in the group's entry. */
    private Optional<NewJournalEntry> personalEntry(
            Mirror mirror,
            EntryType type,
            JournalEntry groupEntry,
            @Nullable ExpenseCategory category,
            String description,
            UUID paymentAccountId,
            UUID ledgerId) {
        GroupShare share = GroupShare.of(groupEntry, mirror.memberAccountId(), Money.zero(PersonalLedgers.CURRENCY));
        if (!share.involved()) {
            return Optional.empty();
        }
        UUID categoryId = category == null ? mirror.categoryId() : categoryFor(category, mirror, ledgerId);
        String text = type == EntryType.EXPENSE ? description : groupAccountName(mirror);
        return Optional.of(new NewJournalEntry(
                ledgerId,
                groupEntry.occurredOn(),
                text,
                mirror.userId(),
                share.postings(type, categoryId, paymentAccountId, mirror.accountId())));
    }

    /**
     * The member's default category with the expense's key, whatever they renamed it to; else an
     * active category called by that known name; else the mirror's category. The listener must not
     * fail on a setting, so the mirror's category is used even when it was archived since.
     */
    private UUID categoryFor(ExpenseCategory category, Mirror mirror, UUID ledgerId) {
        List<Account> active = ledger.accounts(ledgerId).stream()
                .filter(account -> account.kind() == AccountKind.EXPENSE && !account.archived())
                .toList();
        Optional<UUID> byDefault = active.stream()
                .filter(account -> category.key().equals(account.subtype()))
                .map(Account::id)
                .findFirst();
        if (byDefault.isPresent()) {
            return byDefault.get();
        }
        Map<UUID, KnownName> named = keys.of(active.stream().map(Account::id).toList());
        return active.stream()
                .map(Account::id)
                .filter(id -> {
                    KnownName name = named.get(id);
                    return name != null && name.key().equals(category.key());
                })
                .findFirst()
                .orElse(mirror.categoryId());
    }

    /** The one posting of a personal entry that is neither a category nor the group's account. */
    private Optional<UUID> paymentAccountOf(JournalEntry personalEntry, Mirror mirror, UUID ledgerId) {
        Map<UUID, Account> accounts = accountsOf(ledgerId);
        return personalEntry.postings().stream()
                .map(Posting::accountId)
                .filter(id -> !id.equals(mirror.accountId()))
                .filter(id -> {
                    Account account = accounts.get(id);
                    return account != null && UserAccount.Type.of(account).isPresent();
                })
                .findFirst();
    }

    private void close(Mirror mirror) {
        UUID ledgerId = personalLedgers.ledgerOf(mirror.userId());
        adjust(mirror, ledger.balance(mirror.accountId()).negate(), ledgerId);
        ledger.archiveAccount(mirror.accountId());
        store.save(mirror.with(mirror.categoryId(), mirror.paymentAccountId(), false));
    }

    /**
     * Moves the group's account by {@code change} against {@code Equity:Opening balances}: money the
     * member already had or owed before the mirror, so neither income nor spending, as an account's
     * opening balance is.
     */
    private void adjust(Mirror mirror, Money change, UUID ledgerId) {
        if (change.isZero()) {
            return;
        }
        UUID openingBalances = personalLedgers.openingBalancesAccount(ledgerId, PersonalLedgers.CURRENCY);
        ledger.post(new NewJournalEntry(
                ledgerId,
                LocalDate.now(PersonalLedgers.TIME_ZONE),
                groupAccountName(mirror),
                mirror.userId(),
                List.of(new Posting(mirror.accountId(), change), new Posting(openingBalances, change.negate()))));
    }

    private String groupAccountName(Mirror mirror) {
        return ledger.findAccount(mirror.accountId()).orElseThrow().name();
    }

    private JournalEntry groupEntry(UUID entryId) {
        return ledger.findEntry(entryId)
                .orElseThrow(() -> new IllegalStateException("No entry " + entryId + " in a group's ledger"));
    }

    /** The groups module publishes the reversal's event in the reversal's transaction, so it exists. */
    private UUID reversalOf(UUID reversedId) {
        return ledger.history(reversedId).stream()
                .filter(entry -> reversedId.equals(entry.reversesEntryId()))
                .map(JournalEntry::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Entry " + reversedId + " has no reversal"));
    }

    private void checkCategory(UUID categoryId, Map<UUID, Account> accounts) {
        Account category = accounts.get(categoryId);
        if (category == null || (category.kind() != AccountKind.EXPENSE && category.kind() != AccountKind.INCOME)) {
            throw TransactionException.categoryNotFound(categoryId);
        }
        if (category.kind() != AccountKind.EXPENSE) {
            throw TransactionException.categoryKindMismatch(categoryId, UserTransaction.Type.EXPENSE);
        }
        if (category.archived()) {
            throw TransactionException.categoryArchived(categoryId);
        }
    }

    private void checkPaymentAccount(UUID accountId, Map<UUID, Account> accounts) {
        Account account = accounts.get(accountId);
        Optional<UserAccount.Type> type = account == null ? Optional.empty() : UserAccount.Type.of(account);
        if (type.isEmpty()) {
            throw TransactionException.accountNotFound(accountId);
        }
        if (type.get() == UserAccount.Type.GROUP) {
            throw TransactionException.accountFromGroup(accountId);
        }
        if (Objects.requireNonNull(account).archived()) {
            throw TransactionException.accountArchived(accountId);
        }
    }

    private Map<UUID, Account> accountsOf(UUID ledgerId) {
        return ledger.accounts(ledgerId).stream().collect(toMap(Account::id, Function.identity()));
    }
}
