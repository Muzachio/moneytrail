package io.github.muzachio.moneytrail.ledger;

import java.util.Currency;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An account to open in a ledger. {@code subtype} refines the kind for the UI, for example a
 * checking or savings {@link AccountKind#ASSET}.
 */
public record NewAccount(
        UUID ledgerId, AccountKind kind, @Nullable String subtype, String name, Currency currency) {}
