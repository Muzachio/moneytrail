package io.github.muzachio.moneytrail.groups.internal;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A page of a group's expenses, newest first.
 *
 * @param nextCursor the cursor of the next page, or null on the last page
 */
record ExpensePage(List<Expense> items, @Nullable String nextCursor) {

    ExpensePage {
        items = List.copyOf(items);
    }
}
