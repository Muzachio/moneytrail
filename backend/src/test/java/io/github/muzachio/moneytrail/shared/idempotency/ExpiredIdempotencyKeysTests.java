package io.github.muzachio.moneytrail.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.WorkerIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/** The daily cleanup in the worker process, and ShedLock keeping it to one worker at a time. */
class ExpiredIdempotencyKeysTests extends WorkerIntegrationTest {

    private static final String LOCK = "delete-expired-idempotency-keys";

    @Autowired
    private ExpiredIdempotencyKeys cleanup;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private JdbcClient jdbc;

    private UUID userId;

    @BeforeEach
    void createUserAndReleaseTheLock() {
        userId = jdbc.sql("INSERT INTO users (email, name) VALUES (:email, 'Cleanup Test') RETURNING id")
                .param("email", "cleanup-" + UUID.randomUUID() + "@example.com")
                .query(UUID.class)
                .single();
        // A run holds the lock for at least 5 minutes, so each test starts from a released one.
        lockUntil("- interval '1 second'", "a-finished-worker");
    }

    @Test
    void runsEveryDayInTheWorker() {
        assertThat(scheduledTasks.getScheduledTasks())
                .map(scheduled -> scheduled.getTask())
                .filteredOn(task -> task.toString().endsWith("ExpiredIdempotencyKeys.delete"))
                .singleElement()
                .isInstanceOfSatisfying(
                        CronTask.class, task -> assertThat(task.getExpression()).isEqualTo("0 30 3 * * *"));
    }

    @Test
    void deletesTheKeysOlderThan24Hours() {
        insertKey("25-hours-old", "25 hours");
        insertKey("23-hours-old", "23 hours");

        cleanup.delete();

        assertThat(keysOfUser()).containsExactly("23-hours-old");
    }

    @Test
    void skipsTheRunWhileAnotherWorkerHoldsTheLock() {
        insertKey("25-hours-old", "25 hours");
        lockUntil("+ interval '10 minutes'", "another-worker");

        cleanup.delete();
        assertThat(keysOfUser()).containsExactly("25-hours-old");

        lockUntil("- interval '1 second'", "another-worker");
        cleanup.delete();
        assertThat(keysOfUser()).isEmpty();
    }

    /**
     * Sets the job's row as a worker leaves it. ShedLock keeps the row once it exists and only moves
     * {@code lock_until}, and it remembers that the row exists, so a test must not delete it.
     */
    private void lockUntil(String fromNow, String lockedBy) {
        jdbc.sql("""
                        INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
                        VALUES (:name, timezone('utc', now()) %s, timezone('utc', now()), :lockedBy)
                        ON CONFLICT (name) DO UPDATE
                        SET lock_until = excluded.lock_until, locked_at = excluded.locked_at,
                            locked_by = excluded.locked_by""".formatted(fromNow))
                .param("name", LOCK)
                .param("lockedBy", lockedBy)
                .update();
    }

    private void insertKey(String key, String age) {
        jdbc.sql("""
                        INSERT INTO idempotency_keys
                            (user_id, key, request_hash, response_status, response_headers, created_at)
                        VALUES (:userId, :key, sha256(convert_to(:key, 'UTF8')), 204, '{}', now() - CAST(:age AS interval))""")
                .param("userId", userId)
                .param("key", key)
                .param("age", age)
                .update();
    }

    private List<String> keysOfUser() {
        return jdbc.sql("SELECT key FROM idempotency_keys WHERE user_id = :userId ORDER BY key")
                .param("userId", userId)
                .query((rs, rowNum) -> rs.getString("key"))
                .list();
    }
}
