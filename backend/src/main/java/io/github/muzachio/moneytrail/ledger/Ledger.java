package io.github.muzachio.moneytrail.ledger;

import java.util.UUID;

/** A set of accounts whose journal entries balance among themselves. */
public record Ledger(UUID id, LedgerOwner owner) {}
