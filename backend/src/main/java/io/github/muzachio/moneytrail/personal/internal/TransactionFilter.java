package io.github.muzachio.moneytrail.personal.internal;

import java.time.YearMonth;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The transactions list's filters: every one that is set must match.
 *
 * @param text what the description contains, ignoring case
 */
record TransactionFilter(
        YearMonth month,
        UserTransaction.@Nullable Type type,
        @Nullable UUID accountId,
        @Nullable UUID categoryId,
        @Nullable String text) {}
