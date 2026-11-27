package io.github.muzachio.moneytrail.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.WorkerIntegrationTest;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * The daily cleanup, here in the worker process, and ShedLock keeping it to one run at a time
 * whichever processes have the scheduled jobs on.
 */
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
        lockUntil("- interval '1 second'", "a-finished-run");
    }

    @Test
    void runsEveryDayAt3Utc() {
        assertThat(scheduledCleanup().getExpression()).isEqualTo("0 0 3 * * *");
    }

    /**
     * Production deletes expired sessions at the top of every hour (application-prod.yml), which wakes
     * Neon's free compute anyway; the cleanup runs in one of those minutes, so it never wakes it on its
     * own.
     */
    @Test
    void runsInAMinuteOfTheHourlySessionCleanup() throws IOException {
        Clock middayUtc = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);
        Instant nextCleanup = Objects.requireNonNull(
                scheduledCleanup().getTrigger().nextExecution(new SimpleTriggerContext(middayUtc)));
        assertThat(nextCleanup).isEqualTo(Instant.parse("2026-11-20T03:00:00Z"));

        CronExpression sessionCleanup = CronExpression.parse(productionSetting("spring.session.jdbc.cleanup-cron"));
        assertThat(sessionCleanup.next(nextCleanup.minusSeconds(1).atZone(ZoneOffset.UTC)))
                .isEqualTo(nextCleanup.atZone(ZoneOffset.UTC));
    }

    @Test
    void deletesTheKeysOlderThan24Hours() {
        insertKey("25-hours-old", "25 hours");
        insertKey("23-hours-old", "23 hours");

        cleanup.delete();

        assertThat(keysOfUser()).containsExactly("23-hours-old");
    }

    /** An api and a worker with the jobs on at the same time: only one of them deletes. */
    @Test
    void skipsTheRunWhileAnotherProcessHoldsTheLock() {
        insertKey("25-hours-old", "25 hours");
        lockUntil("+ interval '10 minutes'", "another-process");

        cleanup.delete();
        assertThat(keysOfUser()).containsExactly("25-hours-old");

        lockUntil("- interval '1 second'", "another-process");
        cleanup.delete();
        assertThat(keysOfUser()).isEmpty();
    }

    private CronTask scheduledCleanup() {
        List<CronTask> cleanups = scheduledTasks.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(task -> task.toString().endsWith("ExpiredIdempotencyKeys.delete"))
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .toList();
        assertThat(cleanups).hasSize(1);
        return cleanups.getFirst();
    }

    /** A setting of application-prod.yml, which only the production processes load. */
    private static String productionSetting(String key) throws IOException {
        Object value = new YamlPropertySourceLoader()
                .load("application-prod.yml", new ClassPathResource("application-prod.yml"))
                .getFirst()
                .getProperty(key);
        assertThat(value).as(key).isNotNull();
        return String.valueOf(value);
    }

    /**
     * Sets the job's row as another process leaves it. ShedLock keeps the row once it exists and only
     * moves {@code lock_until}, and it remembers that the row exists, so a test must not delete it.
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
