package io.github.muzachio.moneytrail.ledger;

import java.util.Currency;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An account of a ledger. Every posting to it is in its {@code currency}. */
public record Account(
        UUID id, UUID ledgerId, AccountKind kind, @Nullable String subtype, String name, Currency currency) {}
