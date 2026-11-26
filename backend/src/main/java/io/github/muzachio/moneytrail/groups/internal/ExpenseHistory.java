package io.github.muzachio.moneytrail.groups.internal;

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
        Expense expense, List<TrailStep> trail, @Nullable UUID currentId) {

    ExpenseHistory {
        trail = List.copyOf(trail);
    }
}
