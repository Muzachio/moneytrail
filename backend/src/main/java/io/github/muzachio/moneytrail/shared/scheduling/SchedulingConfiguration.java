package io.github.muzachio.moneytrail.shared.scheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Scheduled jobs run in the worker process only (docs/architecture.md), never in the api or
 * migrate ones. ShedLock (ADR-0005) keeps each run of a job to one worker, through a row per job in
 * the {@code shedlock} table (V7); a job whose row is taken is skipped, not queued. The scheduler's
 * thread is also what keeps the worker's JVM running, since it has no web server.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
// The lock outlives a worker that dies mid-job by this much at most; jobs must finish well within it.
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
class SchedulingConfiguration {

    /**
     * {@code usingDbTime()}: lock times come from Postgres's clock, the same for every machine. The
     * transaction manager is the application's (JPA), as ShedLock's README asks with Hibernate.
     */
    @Bean
    LockProvider lockProvider(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .withTransactionManager(transactionManager)
                .usingDbTime()
                .build());
    }
}
