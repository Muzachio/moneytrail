package io.github.muzachio.moneytrail.groups.internal;

import static java.util.stream.Collectors.toSet;

import io.github.muzachio.moneytrail.groups.ExpenseCategory;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What expenses and settlements read the same way from the ledger: their trail, from the history of
 * one of their versions ({@code LedgerService#history}), and who paid.
 */
final class Trails {

    private Trails() {}

    /** A settlement's trail: every version and every reversal of the history, oldest first. */
    static List<TrailStep> of(List<JournalEntry> history) {
        return of(history, Map.of());
    }

    /**
     * An expense's trail, with the category of each version from {@code categories}; a reversal shows
     * the category of the version it cancels, as it shows its amount.
     */
    static List<TrailStep> of(List<JournalEntry> history, Map<UUID, ExpenseCategory> categories) {
        Map<UUID, TrailStep> steps = new HashMap<>();
        List<TrailStep> trail = new ArrayList<>();
        for (JournalEntry version : history) {
            TrailStep step = stepOf(version, steps, categories);
            steps.put(version.id(), step);
            trail.add(step);
        }
        return trail;
    }

    /** The versions of the history that a later entry reversed. */
    static Set<UUID> reversed(List<JournalEntry> history) {
        return history.stream()
                .map(JournalEntry::reversesEntryId)
                .filter(Objects::nonNull)
                .collect(toSet());
    }

    /** The version in effect at the end of the history, or null when its last version was reversed. */
    static @Nullable UUID current(List<JournalEntry> history) {
        JournalEntry last = history.getLast();
        return last.isReversal() ? null : last.id();
    }

    /**
     * The one posting above zero: who paid an expense, or who paid a settlement. Its amount is the
     * expense's total, or the settlement's amount.
     */
    static Posting paid(JournalEntry entry) {
        List<Posting> paid = entry.postings().stream()
                .filter(posting -> posting.amount().minor() > 0)
                .toList();
        if (paid.size() != 1) {
            throw new IllegalStateException("Journal entry " + entry.id() + " has no single posting above zero");
        }
        return paid.getFirst();
    }

    private static TrailStep stepOf(
            JournalEntry entry, Map<UUID, TrailStep> earlierSteps, Map<UUID, ExpenseCategory> categories) {
        UUID reversed = entry.reversesEntryId();
        if (reversed != null) {
            TrailStep cancelled = Objects.requireNonNull(
                    earlierSteps.get(reversed), () -> "The history lists " + reversed + " after its reversal");
            return new TrailStep(
                    entry.id(),
                    TrailStep.Action.REVERSED,
                    cancelled.amount(),
                    cancelled.category(),
                    entry.createdBy(),
                    entry.createdAt(),
                    reversed);
        }
        return new TrailStep(
                entry.id(),
                entry.replacesEntryId() == null ? TrailStep.Action.RECORDED : TrailStep.Action.CORRECTED,
                paid(entry).amount(),
                categories.get(entry.id()),
                entry.createdBy(),
                entry.createdAt(),
                null);
    }
}
