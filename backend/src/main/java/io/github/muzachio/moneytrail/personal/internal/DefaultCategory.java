package io.github.muzachio.moneytrail.personal.internal;

import static io.github.muzachio.moneytrail.ledger.AccountKind.EXPENSE;
import static io.github.muzachio.moneytrail.ledger.AccountKind.INCOME;

import io.github.muzachio.moneytrail.ledger.AccountKind;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The categories every personal ledger starts with, in the order the app lists them (design
 * canvas). The key never changes: the app translates it, so it is kept in the account's {@code
 * subtype}, which the ledger keeps for the UI. The name is the English one, stored so that the
 * database can compare names when the user adds or renames a category. The icon is the design
 * canvas' one for the key, and it never changes either, so it is not stored.
 */
enum DefaultCategory {
    HOUSING(EXPENSE, "housing", "Housing", Icon.HOUSE),
    FOOD(EXPENSE, "food", "Food", Icon.UTENSILS),
    TRANSPORT(EXPENSE, "transport", "Transport", Icon.TRAM_FRONT),
    BILLS(EXPENSE, "bills", "Bills", Icon.RECEIPT),
    SHOPPING(EXPENSE, "shopping", "Shopping", Icon.SHOPPING_BAG),
    LEISURE(EXPENSE, "leisure", "Leisure", Icon.TICKET),
    HEALTH(EXPENSE, "health", "Health", Icon.HEART),
    OTHER(EXPENSE, "other", "Other", Icon.TAG),
    SALARY(INCOME, "salary", "Salary", Icon.BANKNOTE),
    REFUNDS(INCOME, "refunds", "Refunds", Icon.ROTATE_CCW),
    GIFTS(INCOME, "gifts", "Gifts", Icon.GIFT),
    OTHER_INCOME(INCOME, "other-income", "Other income", Icon.TAG);

    private final AccountKind kind;
    private final String key;
    private final String defaultName;
    private final Icon icon;

    DefaultCategory(AccountKind kind, String key, String defaultName, Icon icon) {
        this.kind = kind;
        this.key = key;
        this.defaultName = defaultName;
        this.icon = icon;
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

    Icon icon() {
        return icon;
    }

    static Optional<DefaultCategory> of(AccountKind kind, @Nullable String key) {
        return Arrays.stream(values())
                .filter(category -> category.kind == kind && category.key.equals(key))
                .findFirst();
    }
}
