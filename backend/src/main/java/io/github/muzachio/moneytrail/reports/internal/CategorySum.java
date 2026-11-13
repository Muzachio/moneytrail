package io.github.muzachio.moneytrail.reports.internal;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.YearMonth;
import java.util.UUID;

/**
 * The sum of the postings to one category in one calendar month, signed as postings are: an expense
 * category is debited (positive), an income category credited (negative).
 */
record CategorySum(YearMonth month, UUID categoryId, Kind kind, Money amount) {

    enum Kind {
        EXPENSE,
        INCOME
    }
}
