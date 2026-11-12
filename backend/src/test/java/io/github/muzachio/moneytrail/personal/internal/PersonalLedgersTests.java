package io.github.muzachio.moneytrail.personal.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.identity.UserSignedIn;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The listener on its own, without the identity lock that already makes sign-ins of one account
 * queue up: it must not rely on its caller for one ledger per user.
 */
class PersonalLedgersTests extends PostgresIntegrationTest {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    /**
     * The first sign-in has opened the ledger and not committed yet; the second meets its row and
     * waits. Then the first commits (the second opens nothing and finds the ledger) or rolls back
     * (the second opens it). Either way both succeed and there is one ledger with all its accounts.
     */
    @ParameterizedTest(name = "first one commits: {0}")
    @ValueSource(booleans = {true, false})
    void aSignInThatMeetsAnotherOpeningTheSameLedgerWaitsForIt(boolean firstCommits) throws Exception {
        UUID userId = newUser();
        CountDownLatch firstOpened = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(() -> transactions.executeWithoutResult(status -> {
                events.publishEvent(new UserSignedIn(userId));
                firstOpened.countDown();
                await(finishFirst);
                if (!firstCommits) {
                    status.setRollbackOnly();
                }
            }));
            await(firstOpened);
            Future<?> second = threads.submit(
                    () -> transactions.executeWithoutResult(status -> events.publishEvent(new UserSignedIn(userId))));
            awaitALedgerInsertWaitingForALock();
            finishFirst.countDown();

            // get() rethrows what either sign-in threw.
            first.get(1, TimeUnit.MINUTES);
            second.get(1, TimeUnit.MINUTES);
        } finally {
            threads.shutdownNow();
        }

        assertThat(ledgersOf(userId)).isOne();
        assertThat(accountsOf(userId)).isEqualTo(13);
    }

    @Test
    void refusesToRunOutsideATransaction() {
        UUID userId = newUser();

        assertThatThrownBy(() -> events.publishEvent(new UserSignedIn(userId)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(ledgersOf(userId)).isZero();
    }

    private UUID newUser() {
        return jdbc.sql("INSERT INTO users (email, name) VALUES (:email, 'Personal Test') RETURNING id")
                .param("email", "personal-" + UUID.randomUUID() + "@example.com")
                .query(UUID.class)
                .single();
    }

    private void awaitALedgerInsertWaitingForALock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.sql("""
                            SELECT count(*) FROM pg_stat_activity
                            WHERE datname = current_database() AND wait_event_type = 'Lock'
                              AND query LIKE 'INSERT INTO ledgers%'""").query(Long.class).single();
            if (waiting == 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("The second sign-in never waited for the first one's ledger");
    }

    private long ledgersOf(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM ledgers WHERE owner_type = 'USER' AND owner_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private long accountsOf(UUID userId) {
        return jdbc.sql("""
                        SELECT count(*) FROM accounts JOIN ledgers ON ledgers.id = accounts.ledger_id
                        WHERE ledgers.owner_type = 'USER' AND ledgers.owner_id = :userId""").param("userId", userId).query(Long.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).as("waited 30 s").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
