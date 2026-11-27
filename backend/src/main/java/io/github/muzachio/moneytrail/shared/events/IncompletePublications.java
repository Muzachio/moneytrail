package io.github.muzachio.moneytrail.shared.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.Staleness;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resubmits, at the top of every hour, the event publications whose listener did not complete
 * (ADR-0014). It is the only way they come back, since resubmitting on restart is off
 * (application.yml).
 *
 * <p>A listener that throws leaves its publication failed. One that a process stopped in the middle
 * of, or that never started, leaves it published, processing or resubmitted for good: Spring
 * Modulith's staleness check would mark it failed, but on a timer of its own, with a query that would
 * wake Neon at a minute of its own. So this job marks the ones older than {@link #MIN_AGE} failed
 * first, then resubmits every failed one.
 *
 * <p>Neon's free compute sleeps after a few idle minutes, and its monthly allowance counts the time
 * it is awake. So the job runs at the top of the hour, the minute production deletes expired sessions
 * (application-prod.yml), and never wakes the database on its own; and the gauge reports the number
 * the last run found, kept in memory, so exporting metrics every minute never queries the database.
 */
@Component
@ConditionalOnBooleanProperty("moneytrail.jobs.enabled")
class IncompletePublications {

    /**
     * Younger than this, a publication's listener may still be running, so it is left alone. Older,
     * a listener that still has not completed it is not going to.
     */
    static final Duration MIN_AGE = Duration.ofMinutes(10);

    static final String METRIC = "moneytrail.event.publications.incomplete";

    private static final Logger log = LoggerFactory.getLogger(IncompletePublications.class);

    /** Published, processing and resubmitted publications go stale after {@link #MIN_AGE}. */
    private static final Staleness STALE_AFTER_MIN_AGE = status -> MIN_AGE;

    private final EventPublicationRegistry registry;
    private final FailedEventPublications failed;

    /** Unknown until the first run: no number is better than a zero nobody counted. */
    private volatile double lastFound = Double.NaN;

    IncompletePublications(EventPublicationRegistry registry, FailedEventPublications failed, MeterRegistry meters) {
        this.registry = registry;
        this.failed = failed;
        Gauge.builder(METRIC, this, IncompletePublications::lastFound)
                .description("Event publications at least 10 minutes old and not completed, as the last hourly"
                        + " resubmission found them")
                .register(meters);
    }

    /**
     * Every hour on the hour, on one process only. ShedLock wraps the method in a proxy, so it must be
     * public; the lock is held for at least 5 minutes, so a process whose clock runs a little late
     * finds it taken instead of resubmitting a second time. The listeners run again in their own
     * threads, as they did the first time. When nothing is left over, the run is one query.
     */
    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    @SchedulerLock(name = "resubmit-incomplete-event-publications", lockAtLeastFor = "PT5M")
    public void resubmit() {
        // Fails loudly if the method ever runs without ShedLock's proxy.
        LockAssert.assertLocked();
        int found = registry.findIncompletePublicationsOlderThan(MIN_AGE).size();
        lastFound = found;
        if (found == 0) {
            return;
        }
        log.warn("Resubmitting {} event publications not completed after {}", found, MIN_AGE);
        registry.markStalePublicationsFailed(STALE_AFTER_MIN_AGE);
        failed.resubmit(ResubmissionOptions.defaults().withMinAge(MIN_AGE));
    }

    double lastFound() {
        return lastFound;
    }
}
