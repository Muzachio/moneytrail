package io.github.muzachio.moneytrail.groups.internal;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Which entries of a group's ledger are settlements ({@code settlements}, V17), which only this module
 * writes. Their postings are those of an expense one member paid for one other member, so only this
 * table tells the two apart.
 */
@Repository
class SettlementStore {

    private final JdbcClient jdbc;

    SettlementStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID entryId) {
        jdbc.sql("INSERT INTO settlements (entry_id) VALUES (:entryId)")
                .param("entryId", entryId)
                .update();
    }

    /** The entries among {@code entryIds} that are settlements. */
    Set<UUID> settlementsAmong(Collection<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT entry_id FROM settlements WHERE entry_id IN (:entryIds)")
                .param("entryIds", entryIds)
                .query(UUID.class)
                .list());
    }
}
