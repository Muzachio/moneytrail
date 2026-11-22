package io.github.muzachio.moneytrail.ledger;

import java.util.UUID;

/** Whose money a ledger holds: one person's, or a group's. */
public record LedgerOwner(Type type, UUID id) {

    public enum Type {
        USER,
        GROUP
    }

    public static LedgerOwner user(UUID userId) {
        return new LedgerOwner(Type.USER, userId);
    }

    /** A group's ledger, which holds its members' debts (ADR-0012). */
    public static LedgerOwner group(UUID groupId) {
        return new LedgerOwner(Type.GROUP, groupId);
    }
}
