package io.github.muzachio.moneytrail.groups.internal;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A page of a group's list, expenses and settlements together, newest first.
 *
 * @param nextCursor the cursor of the next page, or null on the last page
 */
record MovementPage(List<Movement> items, @Nullable String nextCursor) {

    MovementPage {
        items = List.copyOf(items);
    }
}
