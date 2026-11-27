package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.core.Staleness;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * Everything that runs on a timer where the scheduled jobs are on (the production api and the
 * worker). Neon's free compute sleeps after a few idle minutes and its monthly allowance counts the
 * time it is awake, so nothing may query the database on a timer more often than hourly, or at a
 * minute other than the top of the hour, when production deletes expired sessions anyway: Spring
 * Modulith's own periodic tasks included, checked here against what they do by default.
 */
class ScheduledDatabaseWorkTests extends WorkerIntegrationTest {

    @Autowired
    private ScheduledTaskHolder scheduledTasks;

    @Autowired
    private Staleness staleness;

    /** Over a whole day, every run of every job of the app falls at the top of an hour. */
    @Test
    void everyJobOfTheAppRunsOnlyAtTheTopOfAnHour() {
        List<CronTask> jobs = tasks().stream()
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .toList();
        assertThat(jobs)
                .isNotEmpty()
                .allSatisfy(job ->
                        assertThat(job.toString()).startsWith(MoneyTrailApplication.class.getPackageName() + "."))
                .allSatisfy(job -> {
                    Instant next = Instant.parse("2026-11-26T00:00:01Z");
                    Instant dayAfter = next.plus(Duration.ofDays(1));
                    while (next.isBefore(dayAfter)) {
                        next = Objects.requireNonNull(job.getTrigger()
                                .nextExecution(new SimpleTriggerContext(Clock.fixed(next, ZoneOffset.UTC))));
                        ZonedDateTime run = next.atZone(ZoneOffset.UTC);
                        assertThat(run.getMinute()).as("%s at %s", job, run).isZero();
                        assertThat(run.getSecond()).as("%s at %s", job, run).isZero();
                    }
                });
    }

    /**
     * Nothing else is on a timer, Spring Modulith's periodic tasks included: Moments is off
     * (application.yml), and its check for stale event publications, every minute by default, is
     * scheduled only when a staleness is set, which none is. The hourly resubmission of shared.events
     * marks stale publications failed instead, at the top of the hour.
     */
    @Test
    void noLibrarySchedulesWorkOfItsOwn() {
        assertThat(tasks()).allSatisfy(task -> assertThat(task).isInstanceOf(CronTask.class));
        assertThat(EventPublication.Status.values()).noneMatch(staleness::isMonitored);
    }

    private List<Task> tasks() {
        return scheduledTasks.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .toList();
    }
}
