package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.ledger.AccountKind;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The categories every personal ledger starts with, in the order the app lists them (design
 * canvas). Each one starts with a name the app knows, and its key is also kept in the account's
 * {@code subtype}, which the ledger keeps for the UI: that one never changes, so it says which
 * default a category is whatever the person later calls it. The icon is the design canvas' one, and
 * it never changes either, so it is not stored.
 */
enum DefaultCategory {
    HOUSING(KnownName.HOUSING, Icon.HOUSE),
    FOOD(KnownName.FOOD, Icon.UTENSILS),
    TRANSPORT(KnownName.TRANSPORT, Icon.TRAM_FRONT),
    BILLS(KnownName.BILLS, Icon.RECEIPT),
    SHOPPING(KnownName.SHOPPING, Icon.SHOPPING_BAG),
    LEISURE(KnownName.LEISURE, Icon.TICKET),
    HEALTH(KnownName.HEALTH, Icon.HEART),
    OTHER(KnownName.OTHER, Icon.TAG),
    SALARY(KnownName.SALARY, Icon.BANKNOTE),
    REFUNDS(KnownName.REFUNDS, Icon.ROTATE_CCW),
    GIFTS(KnownName.GIFTS, Icon.GIFT),
    OTHER_INCOME(KnownName.OTHER_INCOME, Icon.TAG);

    private final KnownName name;
    private final Icon icon;

    DefaultCategory(KnownName name, Icon icon) {
        this.name = name;
        this.icon = icon;
    }

    AccountKind kind() {
        return name.kind();
    }

    String key() {
        return name.key();
    }

    /** The name it starts with. */
    KnownName knownName() {
        return name;
    }

    Icon icon() {
        return icon;
    }

    /** The default category an account is, by the key in its {@code subtype}. */
    static Optional<DefaultCategory> of(AccountKind kind, @Nullable String subtype) {
        return Arrays.stream(values())
                .filter(category -> category.kind() == kind && category.key().equals(subtype))
                .findFirst();
    }
}
