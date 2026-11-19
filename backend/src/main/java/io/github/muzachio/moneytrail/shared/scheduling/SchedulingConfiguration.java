package io.github.muzachio.moneytrail.shared.scheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Scheduled jobs run in a process only when the setting {@code moneytrail.jobs.enabled} is on
 * (application.yml), not because of its profile: the worker turns it on, and in production so does
 * the api while the worker group has no machine (backend/fly.toml). The migrate run never does,
 * since its JVM must exit after the migrations. ShedLock (ADR-0005) keeps each run of a job to one
 * process, through a row per job in the {@code shedlock} table (V7), so an api and a worker that
 * both have the setting on never run a job twice; a job whose row is taken is skipped, not queued.
 * In the worker, the scheduler's thread is also what keeps the JVM running, since it has no web
 * server.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("moneytrail.jobs.enabled")
@EnableScheduling
// The lock outlives a process that dies mid-job by this much at most; jobs must finish well within it.
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
