package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.YearMonth;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A page of a month's transactions, and the month's totals for the same filters.
 *
 * @param nextCursor the cursor of the next page, or null on the last page
 */
record TransactionPage(
        YearMonth month,
        Summary summary,
        List<UserTransaction> items,
        @Nullable String nextCursor) {

    TransactionPage {
        items = List.copyOf(items);
    }

    /**
     * Transfers count as transactions but add nothing to the sums: they only move money between the
     * user's own accounts.
     *
     * @param net income minus spending
     */
    record Summary(long count, Money income, Money spending, Money net) {}
}
