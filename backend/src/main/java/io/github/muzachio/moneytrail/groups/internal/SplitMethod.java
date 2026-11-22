package io.github.muzachio.moneytrail.groups.internal;

/** How an expense is divided among the people in it (ADR-0012). */
enum SplitMethod {
    /** The same weight, 1, for each person. */
    EQUALLY,
    /** Whole numbers above zero: 2 for a couple, 1 for someone alone. */
    SHARES,
    /** Basis points that add up to 10 000, so 33.33 % is 3 333. */
    PERCENTAGES,
    /** Amounts in minor units that add up to the total, used as they are. */
    EXACT_AMOUNTS
}
