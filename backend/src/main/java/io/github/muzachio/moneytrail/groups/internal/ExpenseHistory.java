package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A version of an expense and its trail: every version and every reversal, oldest first, each with
 * the member who made it (ADR-0012: any member may correct an expense, and the trail shows who).
 *
 * @param currentId the version in effect, or null when the last one was reversed without a correction
 */
record ExpenseHistory(
        Expense expense, List<Step> trail, @Nullable UUID currentId) {

    ExpenseHistory {
        trail = List.copyOf(trail);
    }

    enum Action {
        /** The first version. */
        RECORDED,
        /** An entry that cancels the version before it. */
        REVERSED,
        /** A later version, recorded together with the reversal before it. */
        CORRECTED
    }

    /**
     * @param amount the total of the version, or of the version a reversal cancels
     * @param recordedBy who recorded the version, or reversed it
     * @param reversesEntryId the version a reversal cancels; null on the other steps
     */
    record Step(
            UUID entryId,
            Action action,
            Money amount,
            UUID recordedBy,
            Instant recordedAt,
            @Nullable UUID reversesEntryId) {}
}
