package io.github.muzachio.moneytrail.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shows the order in which {@link LedgerService#post} locks balances. The test's transaction locks
 * one balance, a post that needs it starts on another thread and waits, and a third connection
 * checks which of the entry's other balances the waiting post already holds.
 */
final class BalanceLockProbe {

    private final LedgerService ledger;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactionManager;

    BalanceLockProbe(LedgerService ledger, JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.transactionManager = transactionManager;
    }

    /**
     * The accounts in the order post() locks them: Postgres's order of uuids, which is not {@link
     * UUID#compareTo}'s, so it comes from the database.
     */
    List<UUID> inLockOrder(UUID... accountIds) {
        return jdbc
                .sql("SELECT id FROM unnest(:ids) AS id ORDER BY id")
                .param("ids", accountIds)
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .list()
                .stream()
                .map(Objects::requireNonNull)
                .toList();
    }

    /**
     * Locks one balance, starts posting an entry that needs it, and returns the entry's other
     * accounts whose balances the post holds while it waits. Then lets the post finish.
     */
    List<UUID> heldWhilePostWaitsFor(UUID blocked, NewJournalEntry entry) throws Exception {
        Set<UUID> others = new LinkedHashSet<>();
        entry.postings().forEach(posting -> others.add(posting.accountId()));
        others.remove(blocked);
        List<UUID> held = new ArrayList<>();
        CompletableFuture<JournalEntry> waiting = new TransactionTemplate(transactionManager).execute(status -> {
            lockBalance(blocked);
            CompletableFuture<JournalEntry> post = CompletableFuture.supplyAsync(() -> ledger.post(entry));
            assertThatThrownBy(() -> post.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            for (UUID other : others) {
                if (isLockedElsewhere(other)) {
                    held.add(other);
                }
            }
            return post;
        });
        assertThat(waiting).isNotNull();
        waiting.get(10, TimeUnit.SECONDS);
        return held;
    }

    private void lockBalance(UUID accountId) {
        jdbc.sql("SELECT balance_minor FROM account_balances WHERE account_id = :id FOR UPDATE")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }

    // From a third connection: NOWAIT fails at once if another transaction holds the row.
    private boolean isLockedElsewhere(UUID accountId) {
        TransactionTemplate separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            separate.executeWithoutResult(status -> {
                status.setRollbackOnly();
                jdbc.sql("SELECT balance_minor FROM account_balances WHERE account_id = :id FOR UPDATE NOWAIT")
                        .param("id", accountId)
                        .query(Long.class)
                        .single();
            });
            return false;
        } catch (DataAccessException exception) {
            // 55P03 lock_not_available; Spring leaves it uncategorized.
            if (exception.getMostSpecificCause() instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                return true;
            }
            throw exception;
        }
    }
}
