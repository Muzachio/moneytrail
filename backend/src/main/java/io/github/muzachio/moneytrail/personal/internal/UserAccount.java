package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.util.Arrays;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An account as the API shows it: one of the user's {@code ASSET} or {@code LIABILITY} accounts,
 * with its balance and how much it changed this month. Both are signed as the ledger keeps them, so
 * a credit card's debt is negative.
 */
record UserAccount(
        UUID id,
        Type type,
        String name,
        Currency currency,
        Money balance,
        Money changeThisMonth,
        @Nullable Instant archivedAt) {

    /**
     * The kinds of account the user can open. The ledger keeps the kind and, in the account's
     * {@code subtype}, the key, which never changes.
     */
    enum Type {
        CHECKING(AccountKind.ASSET, "checking"),
        SAVINGS(AccountKind.ASSET, "savings"),
        CASH(AccountKind.ASSET, "cash"),
        CREDIT_CARD(AccountKind.LIABILITY, "credit-card");

        private final AccountKind kind;
        private final String key;

        Type(AccountKind kind, String key) {
            this.kind = kind;
            this.key = key;
        }

        AccountKind kind() {
            return kind;
        }

        String key() {
            return key;
        }

        /** Empty for a ledger account that is not one of the user's accounts, such as a category. */
        static Optional<Type> of(Account account) {
            return Arrays.stream(values())
                    .filter(type -> type.kind == account.kind() && type.key.equals(account.subtype()))
                    .findFirst();
        }
    }

    boolean archived() {
        return archivedAt != null;
    }
}
