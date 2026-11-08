package io.github.muzachio.moneytrail.ledger;

import java.util.UUID;

/** Whose money a ledger holds: one person's, or (from M2) a group's. */
public record LedgerOwner(Type type, UUID id) {

    public enum Type {
        USER,
        GROUP
    }

    public static LedgerOwner user(UUID userId) {
        return new LedgerOwner(Type.USER, userId);
    }
}
