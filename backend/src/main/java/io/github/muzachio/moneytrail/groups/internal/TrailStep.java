package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One step in the trail of an expense or a settlement: a version of it, or the reversal of one, with
 * the member who made it (ADR-0012: any member may correct either, and the trail shows who).
 *
 * @param amount the amount of the version, or of the version a reversal cancels
 * @param recordedBy who recorded the version, or reversed it
 * @param reversesEntryId the version a reversal cancels; null on the other steps
 */
record TrailStep(
        UUID entryId,
        Action action,
        Money amount,
        UUID recordedBy,
        Instant recordedAt,
        @Nullable UUID reversesEntryId) {

    enum Action {
        /** The first version. */
        RECORDED,
        /** An entry that cancels the version before it. */
        REVERSED,
        /** A later version, recorded together with the reversal before it. */
        CORRECTED
    }
}
