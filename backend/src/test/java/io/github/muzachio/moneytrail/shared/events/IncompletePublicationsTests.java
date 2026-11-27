package io.github.muzachio.moneytrail.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.muzachio.moneytrail.WorkerIntegrationTest;
import io.github.muzachio.moneytrail.groups.ExpenseCategory;
import io.github.muzachio.moneytrail.groups.GroupExpenseAdded;
import io.github.muzachio.moneytrail.shared.Money;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The hourly resubmission, here in the worker process, with the activity feed's listener as the one
 * that fails: an expense of a group that does not exist yet breaks the item's foreign key, so the
 * listener throws, and once the group exists the resubmission completes it.
 */
class IncompletePublicationsTests extends WorkerIntegrationTest {

    private static final String LOCK = "resubmit-incomplete-event-publications";
    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired
    private IncompletePublications job;

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ApplicationEventPublisher events;

    private UUID ana;

    @BeforeEach
    void releaseTheLockAndForgetOtherTestsPublications() {
        // A run holds the lock for at least 5 minutes, so each test starts from a released one.
        lockUntil("- interval '1 second'", "a-finished-run");
        jdbc.sql("DELETE FROM event_publication").update();
        ana = newUser("Ana");
    }

    @Test
    void runsAtTheTopOfEveryHourUtc() {
        assertThat(scheduledJob().getExpression()).isEqualTo("0 0 * * * *");
        Clock afternoonUtc = Clock.fixed(Instant.parse("2026-11-26T14:20:00Z"), ZoneOffset.UTC);
        assertThat(scheduledJob().getTrigger().nextExecution(new SimpleTriggerContext(afternoonUtc)))
                .isEqualTo(Instant.parse("2026-11-26T15:00:00Z"));
    }

    /**
     * Production deletes expired sessions at the top of every hour (application-prod.yml), which wakes
     * Neon's free compute anyway; the job runs in those minutes, so it never wakes it on its own.
     */
    @Test
    void runsInTheMinutesOfTheHourlySessionCleanup() throws IOException {
        CronExpression sessionCleanup = CronExpression.parse(productionSetting("spring.session.jdbc.cleanup-cron"));
        Instant next = Instant.parse("2026-11-26T14:20:00Z");
        for (int run = 0; run < 24; run++) {
            next = Objects.requireNonNull(scheduledJob()
                    .getTrigger()
                    .nextExecution(new SimpleTriggerContext(Clock.fixed(next, ZoneOffset.UTC))));
            assertThat(sessionCleanup.next(next.minusSeconds(1).atZone(ZoneOffset.UTC)))
                    .isEqualTo(next.atZone(ZoneOffset.UTC));
        }
    }

    @Test
    void aListenerThatFailedIsResubmittedAndCompletes() {
        UUID group = UUID.randomUUID();
        UUID expense = publishExpenseOf(group);
        awaitStatus("FAILED");

        newGroup(group);
        makePublicationsOlderThanTheMinimumAge();
        job.resubmit();

        await().atMost(Duration.ofSeconds(10)).until(() -> itemsOf(expense) == 1 && publicationsLeft() == 0);
        assertThat(job.lastFound()).isEqualTo(1);
    }

    /**
     * A process stopped while the listener ran leaves the publication processing, with no failure on
     * record; the job marks it failed and resubmits it all the same.
     */
    @Test
    void aPublicationAProcessLeftInTheMiddleIsResubmitted() {
        UUID group = UUID.randomUUID();
        UUID expense = publishExpenseOf(group);
        awaitStatus("FAILED");
        jdbc.sql("UPDATE event_publication SET status = 'PROCESSING'").update();

        newGroup(group);
        makePublicationsOlderThanTheMinimumAge();
        job.resubmit();

        await().atMost(Duration.ofSeconds(10)).until(() -> itemsOf(expense) == 1 && publicationsLeft() == 0);
    }

    /** Its listener may still be running, so the job leaves it until it is older. */
    @Test
    void aPublicationYoungerThan10MinutesIsLeftAlone() {
        UUID group = UUID.randomUUID();
        publishExpenseOf(group);
        awaitStatus("FAILED");
        newGroup(group);

        job.resubmit();

        assertThat(job.lastFound()).isZero();
        assertThat(statuses()).containsExactly("FAILED");
    }

    /** Exporting metrics every minute reads the last run's number and never queries the database. */
    @Test
    void theGaugeReportsWhatTheLastRunFoundUntilTheNextRun() {
        job.resubmit();
        assertThat(gauge()).isZero();

        publishExpenseOf(UUID.randomUUID());
        awaitStatus("FAILED");
        makePublicationsOlderThanTheMinimumAge();
        assertThat(gauge()).as("between runs").isZero();

        // The next run, an hour later, once the lock of the first has run out.
        lockUntil("- interval '1 second'", "the-first-run");
        job.resubmit();
        assertThat(gauge()).isEqualTo(1);
    }

    /** An api and a worker with the jobs on at the same time: only one of them resubmits. */
    @Test
    void skipsTheRunWhileAnotherProcessHoldsTheLock() {
        UUID group = UUID.randomUUID();
        UUID expense = publishExpenseOf(group);
        awaitStatus("FAILED");
        newGroup(group);
        makePublicationsOlderThanTheMinimumAge();
        lockUntil("+ interval '10 minutes'", "another-process");

        job.resubmit();
        assertThat(statuses()).containsExactly("FAILED");

        lockUntil("- interval '1 second'", "another-process");
        job.resubmit();
        await().atMost(Duration.ofSeconds(10)).until(() -> itemsOf(expense) == 1 && publicationsLeft() == 0);
    }

    private double gauge() {
        return meters.get(IncompletePublications.METRIC).gauge().value();
    }

    /** Published in a transaction, as the groups module does; the listener runs once it commits. */
    private UUID publishExpenseOf(UUID group) {
        UUID expense = UUID.randomUUID();
        transactions.executeWithoutResult(status -> events.publishEvent(new GroupExpenseAdded(
                group,
                expense,
                "Dinner",
                ExpenseCategory.FOOD,
                new Money(3000, EUR),
                LocalDate.of(2026, 11, 20),
                ana,
                ana,
                Instant.now())));
        return expense;
    }

    private void awaitStatus(String status) {
        await().atMost(Duration.ofSeconds(10)).until(() -> statuses().equals(List.of(status)));
    }

    private List<String> statuses() {
        return jdbc.sql("SELECT status FROM event_publication")
                .query((rs, rowNum) -> String.valueOf(rs.getString("status")))
                .list();
    }

    /**
     * Both dates Spring Modulith goes by: the staleness check reads the last resubmission's when there
     * is one, and the publication's otherwise.
     */
    private void makePublicationsOlderThanTheMinimumAge() {
        jdbc.sql("""
                        UPDATE event_publication
                        SET publication_date = now() - interval '11 minutes',
                            last_resubmission_date = CASE WHEN last_resubmission_date IS NOT NULL
                                THEN now() - interval '11 minutes' END""").update();
    }

    private long publicationsLeft() {
        return jdbc.sql("SELECT count(*) FROM event_publication")
                .query(Long.class)
                .single();
    }

    private long itemsOf(UUID expense) {
        return jdbc.sql("SELECT count(*) FROM activity_items WHERE subject_id = :expense")
                .param("expense", expense)
                .query(Long.class)
                .single();
    }

    private CronTask scheduledJob() {
        List<CronTask> jobs = scheduledTasks.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(task -> task.toString().endsWith("IncompletePublications.resubmit"))
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .toList();
        assertThat(jobs).hasSize(1);
        return jobs.getFirst();
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

    /** As in ExpiredIdempotencyKeysTests: ShedLock keeps the row and only moves lock_until. */
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

    private UUID newUser(String name) {
        return jdbc.sql("INSERT INTO users (email, name) VALUES (:email, :name) RETURNING id")
                .param("email", "outbox-" + UUID.randomUUID() + "@example.com")
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    /** The rows the groups module would make, with the id the event already named. */
    private void newGroup(UUID id) {
        UUID ledger = jdbc.sql("INSERT INTO ledgers (owner_type, owner_id) VALUES ('GROUP', :id) RETURNING id")
                .param("id", id)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                        INSERT INTO groups (id, name, currency, ledger_id, created_by)
                        VALUES (:id, 'Porto weekend', 'EUR', :ledger, :createdBy)""")
                .param("id", id)
                .param("ledger", ledger)
                .param("createdBy", ana)
                .update();
    }
}
