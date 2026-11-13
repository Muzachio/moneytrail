package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A version of a transaction with the postings it was recorded as and its trail: every version and
 * every reversal, oldest first (the design's "Trail").
 *
 * @param currentId the version in effect, or null when the last one was reversed without a correction
 */
record TransactionHistory(
        UserTransaction transaction,
        List<Posting> postings,
        List<Step> trail,
        @Nullable UUID currentId) {

    TransactionHistory {
        postings = List.copyOf(postings);
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
     * @param type and {@code amount}: those of the version, or of the version a reversal cancels
     * @param reversesEntryId the version a reversal cancels; null on the other steps
     */
    record Step(
            UUID entryId,
            Action action,
            UserTransaction.Type type,
            Money amount,
            Instant recordedAt,
            @Nullable UUID reversesEntryId) {}
}
