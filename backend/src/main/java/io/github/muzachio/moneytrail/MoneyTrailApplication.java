package io.github.muzachio.moneytrail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class MoneyTrailApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(MoneyTrailApplication.class, args);
        // Fly.io's release_command (application-migrate.yml, ADR-0009): Flyway migrated the database
        // while the context started, so the work is done. Exiting here hands Fly.io exit code 0
        // even when something keeps the JVM alive, such as a scheduler. A failed migration never
        // gets here: run() throws and the JVM exits with 1, which stops the deploy.
        if (context.getEnvironment().matchesProfiles("migrate")) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
