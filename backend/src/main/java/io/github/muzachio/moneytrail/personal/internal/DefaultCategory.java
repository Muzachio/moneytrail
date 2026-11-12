package io.github.muzachio.moneytrail.personal.internal;

import static io.github.muzachio.moneytrail.ledger.AccountKind.EXPENSE;
import static io.github.muzachio.moneytrail.ledger.AccountKind.INCOME;

import io.github.muzachio.moneytrail.ledger.AccountKind;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The categories every personal ledger starts with, in the order the app lists them (design
 * canvas). The key never changes: the app translates it and picks an icon from it, so it is kept in
 * the account's {@code subtype}, which the ledger keeps for the UI. The name is the English one,
 * stored so that the database can compare names when the user adds or renames a category.
 */
enum DefaultCategory {
    HOUSING(EXPENSE, "housing", "Housing"),
    FOOD(EXPENSE, "food", "Food"),
    TRANSPORT(EXPENSE, "transport", "Transport"),
    BILLS(EXPENSE, "bills", "Bills"),
    SHOPPING(EXPENSE, "shopping", "Shopping"),
    LEISURE(EXPENSE, "leisure", "Leisure"),
    HEALTH(EXPENSE, "health", "Health"),
    OTHER(EXPENSE, "other", "Other"),
    SALARY(INCOME, "salary", "Salary"),
    REFUNDS(INCOME, "refunds", "Refunds"),
    GIFTS(INCOME, "gifts", "Gifts"),
    OTHER_INCOME(INCOME, "other-income", "Other income");

    private final AccountKind kind;
    private final String key;
    private final String defaultName;

    DefaultCategory(AccountKind kind, String key, String defaultName) {
        this.kind = kind;
        this.key = key;
        this.defaultName = defaultName;
    }

    AccountKind kind() {
        return kind;
    }

    String key() {
        return key;
    }

    String defaultName() {
        return defaultName;
    }

    static Optional<DefaultCategory> of(AccountKind kind, @Nullable String key) {
        return Arrays.stream(values())
                .filter(category -> category.kind == kind && category.key.equals(key))
                .findFirst();
    }
}
