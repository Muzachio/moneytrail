package io.github.muzachio.moneytrail.ledger.internal;

import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain SQL for what JPA cannot say: an insert that gives way to a row another transaction added. */
@Repository
class LedgerInserts {

    private final JdbcClient jdbc;

    LedgerInserts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the owner's ledger unless it has one. Against the unique (owner_type, owner_id)
     * constraint, an insert that meets a row added by a transaction still running waits for it:
     * after a commit it inserts nothing and returns empty, instead of failing the way a plain
     * INSERT would, which would also abort the caller's transaction (the sign-in).
     */
    Optional<UUID> insertIfAbsent(LedgerOwner owner) {
        return jdbc.sql("""
                        INSERT INTO ledgers (owner_type, owner_id) VALUES (:ownerType, :ownerId)
                        ON CONFLICT (owner_type, owner_id) DO NOTHING
                        RETURNING id""")
                .param("ownerType", owner.type().name())
                .param("ownerId", owner.id())
                .query(UUID.class)
                .optional();
    }
}
