package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A category as the API shows it: called by a known name ({@code key}), which the app shows in each
 * person's language, or by a name the person typed ({@code name}), never both. A default category
 * keeps its icon and its place in the list whatever it is called; one of the user's own has the icon
 * picked for it, or the plain tag.
 *
 * @param defaultCategory which default the category is, if it is one
 */
record UserCategory(
        UUID id,
        AccountKind kind,
        @Nullable KnownName key,
        @Nullable String name,
        @Nullable DefaultCategory defaultCategory,
        Icon icon,
        boolean archived) {

    /**
     * @param knownNames the known names of the categories, by id; the account's is missing when it
     *     is called by a typed name
     * @param pickedIcons the icons picked for the user's own categories, by id; the account's may be
     *     missing
     */
    static UserCategory of(Account account, Map<UUID, KnownName> knownNames, Map<UUID, Icon> pickedIcons) {
        Optional<DefaultCategory> defaultCategory = DefaultCategory.of(account.kind(), account.subtype());
        KnownName key = knownNames.get(account.id());
        return new UserCategory(
                account.id(),
                account.kind(),
                key,
                key == null ? account.name() : null,
                defaultCategory.orElse(null),
                defaultCategory
                        .map(DefaultCategory::icon)
                        .orElseGet(() -> pickedIcons.getOrDefault(account.id(), Icon.TAG)),
                account.archived());
    }

    boolean isDefault() {
        return defaultCategory != null;
    }
}
