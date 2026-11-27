package io.github.muzachio.moneytrail.groups;

import java.util.Arrays;

/**
 * What a group expense was for (#117): the keys of the default expense categories, so the app shows it
 * with the same name and icon as a person's own category, and a mirror into a person's ledger (#94) can
 * file it under their category with the same key. In the module's API because the expense events carry
 * it.
 *
 * <p>The list is written out here, not taken from {@code personal}: {@code groups} must not depend on
 * {@code personal}'s internals, and the mirror will make {@code personal} depend on {@code groups}. The
 * same eight keys are api/openapi.yaml's {@code GroupExpenseCategory}, the first eight of its {@code
 * CategoryKey}, and the database's check (V18); a test keeps the three equal.
 *
 * <p>A label kept by {@code groups} next to the expense, never a ledger account: a group's ledger has
 * only its members' accounts, which is why their balances always add up to zero (ADR-0012).
 */
public enum ExpenseCategory {
    HOUSING("housing"),
    FOOD("food"),
    TRANSPORT("transport"),
    BILLS("bills"),
    SHOPPING("shopping"),
    LEISURE("leisure"),
    HEALTH("health"),
    OTHER("other");

    private final String key;

    ExpenseCategory(String key) {
        this.key = key;
    }

    /** The key as the API and the database write it, such as {@code food}. */
    public String key() {
        return key;
    }

    public static ExpenseCategory ofKey(String key) {
        return Arrays.stream(values())
                .filter(category -> category.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown expense category " + key));
    }
}
