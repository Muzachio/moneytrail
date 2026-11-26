package io.github.muzachio.moneytrail.groups.internal;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A version of a settlement and its trail: every version and every reversal, oldest first, each with
 * the member who made it.
 *
 * @param currentId the version in effect, or null when the last one was reversed without a correction
 */
record SettlementHistory(
        Settlement settlement,
        List<TrailStep> trail,
        @Nullable UUID currentId) {

    SettlementHistory {
        trail = List.copyOf(trail);
    }
}
