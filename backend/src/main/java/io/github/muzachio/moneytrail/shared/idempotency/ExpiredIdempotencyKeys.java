package io.github.muzachio.moneytrail.shared.idempotency;

import java.time.Duration;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the keys older than 24 hours, once a day, in a process that runs the scheduled jobs
 * (shared.scheduling). Clients retry within seconds or minutes, so a day is plenty; a key therefore
 * lives between 24 and 48 hours.
 */
@Component
@ConditionalOnBooleanProperty("moneytrail.jobs.enabled")
class ExpiredIdempotencyKeys {

    static final Duration KEPT_FOR = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(ExpiredIdempotencyKeys.class);

    private final IdempotencyKeys keys;

    ExpiredIdempotencyKeys(IdempotencyKeys keys) {
        this.keys = keys;
    }

    /**
     * Every day at 03:00 UTC, in one process only. ShedLock wraps the method in a proxy, so it must
     * be public; the lock is held for at least 5 minutes, so a process whose clock runs a little late
     * finds it taken instead of deleting a second time.
     */
    // 03:00 sharp: Neon's free compute stops after a few idle minutes and wakes on the next query,
    // and Spring Session's hourly cleanup (cleanup-cron in application-prod.yml) already wakes it at
    // the top of every hour. In that minute this job adds no wake-up of its own; any other minute
    // would add one a day.
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @SchedulerLock(name = "delete-expired-idempotency-keys", lockAtLeastFor = "PT5M")
    public void delete() {
        // Fails loudly if the method ever runs without ShedLock's proxy.
        LockAssert.assertLocked();
        int deleted = keys.deleteOlderThan(KEPT_FOR);
        log.info("Deleted {} idempotency keys older than {}", deleted, KEPT_FOR);
    }
}
