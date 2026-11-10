package io.github.muzachio.moneytrail.shared.idempotency;

import java.time.Duration;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the keys older than 24 hours, once a day in the worker process. Clients retry within
 * seconds or minutes, so a day is plenty; a key therefore lives between 24 and 48 hours.
 */
@Component
@Profile("worker")
class ExpiredIdempotencyKeys {

    static final Duration KEPT_FOR = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(ExpiredIdempotencyKeys.class);

    private final IdempotencyKeys keys;

    ExpiredIdempotencyKeys(IdempotencyKeys keys) {
        this.keys = keys;
    }

    /**
     * Every day at 03:30 UTC, on one worker only. ShedLock wraps the method in a proxy, so it must
     * be public; the lock is held for at least 5 minutes, so a worker whose clock runs a little late
     * finds it taken instead of deleting a second time.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @SchedulerLock(name = "delete-expired-idempotency-keys", lockAtLeastFor = "PT5M")
    public void delete() {
        // Fails loudly if the method ever runs without ShedLock's proxy.
        LockAssert.assertLocked();
        int deleted = keys.deleteOlderThan(KEPT_FOR);
        log.info("Deleted {} idempotency keys older than {}", deleted, KEPT_FOR);
    }
}
