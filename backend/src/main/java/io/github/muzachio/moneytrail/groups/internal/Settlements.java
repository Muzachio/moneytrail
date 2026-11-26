package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.groups.SettlementEdited;
import io.github.muzachio.moneytrail.groups.SettlementRecorded;
import io.github.muzachio.moneytrail.groups.SettlementReversed;
import io.github.muzachio.moneytrail.groups.internal.GroupStore.Lock;
import io.github.muzachio.moneytrail.groups.internal.GroupStore.Row;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.DomainException;
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
 * The settlements of a group (ADR-0012): payments from one member to another, recorded to settle up.
 * Each version is one journal entry of the group's ledger, {@code from +amount} and {@code to
 * −amount}, marked as a settlement in {@code settlements}. Like an expense, a settlement is never
 * changed: editing reverses it and posts the correction, and any member may record, edit or reverse
 * any settlement, each version keeping who made it.
 */
@Service
@Transactional
class Settlements {

    /**
     * The description of every settlement's entry. The ledger needs one; the API shows a settlement
     * by who paid whom, so nobody reads it.
     */
    static final String DESCRIPTION = "Settlement";

    private final Groups groups;
    private final GroupStore groupStore;
    private final SettlementStore store;
    private final LedgerService ledger;
    private final ApplicationEventPublisher events;

    Settlements(
            Groups groups,
            GroupStore groupStore,
            SettlementStore store,
            LedgerService ledger,
            ApplicationEventPublisher events) {
        this.groups = groups;
        this.groupStore = groupStore;
        this.store = store;
        this.ledger = ledger;
        this.events = events;
    }

    /**
     * Under {@code Idempotency-Key} this runs in the transaction that stores the response, so a retry
     * finds the entry, its row and the response, or none of them.
     */
    Settlement record(UUID userId, UUID groupId, NewSettlement settlement) {
        Row group = groups.forPosting(userId, groupId);
        checkCurrency(settlement, group);
        Map<UUID, UUID> accounts = lockMembers(group, people(settlement), Set.of(), null);
        JournalEntry entry = ledger.post(entryOf(group, settlement, accounts, userId));
        store.insert(entry.id());
        events.publishEvent(new SettlementRecorded(
                group.id(),
                entry.id(),
                settlement.amount(),
                entry.occurredOn(),
                settlement.from(),
                settlement.to(),
                userId,
                entry.createdAt()));
        return new Settlement(
                entry.id(),
                settlement.amount(),
                entry.occurredOn(),
                settlement.from(),
                settlement.to(),
                userId,
                entry.createdAt(),
                false,
                false);
    }

    @Transactional(readOnly = true)
    SettlementHistory find(UUID userId, UUID groupId, UUID settlementId) {
        Row group = groupStore
                .findForMember(groupId, userId, Lock.NONE)
                .orElseThrow(() -> GroupException.notFound(groupId));
        return historyOf(group, settlementId);
    }

    /**
     * Reverses the settlement, and records its correction when there is a {@code replacement}, in this
     * one database transaction, as for an expense. A settlement from or to someone who left cannot be
     * reversed: that would change the balance of a person who is not in the group (ADR-0012).
     *
     * @return the correction's history, or the reversed settlement's when there is no replacement
     */
    SettlementHistory reverse(UUID userId, UUID groupId, UUID settlementId, @Nullable NewSettlement replacement) {
        Row group = groups.forPosting(userId, groupId);
        Settlement reversed = historyOf(group, settlementId).settlement();
        if (reversed.reversed()) {
            throw SettlementException.alreadyReversed(settlementId);
        }
        Set<UUID> moved = Set.of(reversed.from(), reversed.to());
        if (replacement == null) {
            lockMembers(group, Set.of(), moved, settlementId);
            JournalEntry reversal = reversing(settlementId, () -> ledger.reverse(settlementId, userId));
            events.publishEvent(new SettlementReversed(
                    group.id(),
                    settlementId,
                    reversed.amount(),
                    reversed.from(),
                    reversed.to(),
                    userId,
                    reversal.createdAt()));
            return historyOf(group, settlementId);
        }
        checkCurrency(replacement, group);
        Map<UUID, UUID> accounts = lockMembers(group, people(replacement), moved, settlementId);
        JournalEntry correction = reversing(
                settlementId, () -> ledger.replace(settlementId, entryOf(group, replacement, accounts, userId)));
        store.insert(correction.id());
        events.publishEvent(new SettlementEdited(
                group.id(),
                correction.id(),
                settlementId,
                replacement.amount(),
                correction.occurredOn(),
                replacement.from(),
                replacement.to(),
                reversed.amount(),
                userId,
                correction.createdAt()));
        return historyOf(group, correction.id());
    }

    /**
     * Reads a settlement's entry back: the posting above zero is who paid, the one below zero who was
     * paid.
     */
    static Settlement settlementOf(JournalEntry entry, Map<UUID, UUID> owners, boolean reversed) {
        List<Posting> postings = entry.postings();
        if (postings.size() != 2) {
            throw new IllegalStateException("Journal entry " + entry.id() + " does not read back as a settlement");
        }
        Posting paid = Trails.paid(entry);
        Posting received = postings.stream()
                .filter(posting -> posting.amount().minor() < 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Journal entry " + entry.id() + " pays nobody"));
        return new Settlement(
                entry.id(),
                paid.amount(),
                entry.occurredOn(),
                ownerOf(owners, paid),
                ownerOf(owners, received),
                entry.createdBy(),
                entry.createdAt(),
                entry.replacesEntryId() != null,
                reversed);
    }

    private static UUID ownerOf(Map<UUID, UUID> owners, Posting posting) {
        return Objects.requireNonNull(
                owners.get(posting.accountId()), () -> "Account " + posting.accountId() + " is no member's");
    }

    private static void checkCurrency(NewSettlement settlement, Row group) {
        if (!settlement.amount().currency().equals(group.currency())) {
            throw SettlementException.currencyMismatch(
                    settlement.amount().currency().getCurrencyCode());
        }
    }

    private static Set<UUID> people(NewSettlement settlement) {
        Set<UUID> people = new LinkedHashSet<>();
        people.add(settlement.from());
        people.add(settlement.to());
        return people;
    }

    /**
     * As for an expense: locks the member rows of everyone the request posts to or reverses, and
     * returns their accounts. {@code reversing}: who paid and who was paid in the reversed version,
     * who must still be members ({@code group_settlement_member_left} otherwise). {@code posting}: who
     * a new version posts to, who must be members ({@code member_not_found}).
     */
    private Map<UUID, UUID> lockMembers(
            Row group, Set<UUID> posting, Set<UUID> reversing, @Nullable UUID reversedSettlementId) {
        Set<UUID> everyone = new LinkedHashSet<>(reversing);
        everyone.addAll(posting);
        Map<UUID, UUID> accounts = groupStore.lockCurrentMembers(group.id(), everyone);
        if (reversedSettlementId != null && !accounts.keySet().containsAll(reversing)) {
            throw SettlementException.memberLeft(reversedSettlementId);
        }
        posting.stream()
                .filter(userId -> !accounts.containsKey(userId))
                .min((a, b) -> a.toString().compareTo(b.toString()))
                .ifPresent(userId -> {
                    throw SettlementException.memberNotFound(userId);
                });
        return accounts;
    }

    /** {@code from +amount}, {@code to −amount} (ADR-0012). */
    private static NewJournalEntry entryOf(
            Row group, NewSettlement settlement, Map<UUID, UUID> accounts, UUID recordedBy) {
        return new NewJournalEntry(
                group.ledgerId(),
                settlement.occurredOn(),
                DESCRIPTION,
                recordedBy,
                List.of(
                        new Posting(GroupExpenses.accountOf(accounts, settlement.from()), settlement.amount()),
                        new Posting(
                                GroupExpenses.accountOf(accounts, settlement.to()),
                                settlement.amount().negate())));
    }

    /** As for an expense: a concurrent reversal that commits first makes the ledger refuse this one. */
    private static JournalEntry reversing(UUID settlementId, Supplier<JournalEntry> reversal) {
        try {
            return reversal.get();
        } catch (DomainException e) {
            if ("entry_already_reversed".equals(e.code())) {
                throw SettlementException.alreadyReversed(settlementId);
            }
            throw e;
        }
    }

    /**
     * The version {@code settlementId} of a settlement of this group, with every version and reversal
     * of it. An id of another ledger, or of an entry that is not a settlement, such as an expense or a
     * reversal, is not found.
     */
    private SettlementHistory historyOf(Row group, UUID settlementId) {
        List<JournalEntry> entries = ledger.history(settlementId);
        JournalEntry entry = entries.stream()
                .filter(found -> found.id().equals(settlementId)
                        && found.ledgerId().equals(group.ledgerId())
                        && !found.isReversal())
                .findFirst()
                .orElseThrow(() -> SettlementException.notFound(settlementId));
        if (store.settlementsAmong(List.of(settlementId)).isEmpty()) {
            throw SettlementException.notFound(settlementId);
        }
        Settlement settlement = settlementOf(
                entry,
                groupStore.accountOwners(group.id()),
                Trails.reversed(entries).contains(settlementId));
        return new SettlementHistory(settlement, Trails.of(entries), Trails.current(entries));
    }
}
