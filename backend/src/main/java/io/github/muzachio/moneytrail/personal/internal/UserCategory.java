package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A category as the API shows it: the key of a default category, and a name only once the user has
 * given one, so that the app shows a default category in the user's language.
 */
record UserCategory(
        UUID id,
        AccountKind kind,
        @Nullable String key,
        @Nullable String name,
        boolean archived) {

    static UserCategory of(Account account) {
        Optional<DefaultCategory> defaultCategory = DefaultCategory.of(account.kind(), account.subtype());
        boolean renamed = defaultCategory
                .map(category -> !category.defaultName().equals(account.name()))
                .orElse(true);
        return new UserCategory(
                account.id(),
                account.kind(),
                defaultCategory.map(DefaultCategory::key).orElse(null),
                renamed ? account.name() : null,
                account.archived());
    }
}
