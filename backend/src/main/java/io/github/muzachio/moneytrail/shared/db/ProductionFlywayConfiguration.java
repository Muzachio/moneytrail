package io.github.muzachio.moneytrail.shared.db;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * In production only Fly.io's release_command changes the schema (the {@code migrate} profile,
 * ADR-0009). The api and worker processes check that the database has every migration in their
 * image and refuse to start otherwise, so a machine never serves code whose tables are missing.
 * Migrations newer than the image are ignored, so an older image still starts after a rollback.
 * Locally and in tests, Flyway still migrates at startup.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod & !migrate")
class ProductionFlywayConfiguration {

    @Bean
    FlywayMigrationStrategy validateInsteadOfMigrate() {
        return Flyway::validate;
    }
}
