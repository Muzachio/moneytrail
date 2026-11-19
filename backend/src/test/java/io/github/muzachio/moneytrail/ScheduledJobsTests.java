package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Which processes run the scheduled jobs: the setting {@code moneytrail.jobs.enabled} as Spring Boot
 * resolves it at startup, from the arguments each process gets in fly.toml and the application*.yml
 * files. The scheduler and the jobs exist exactly where it is on (ExpiredIdempotencyKeysTests,
 * IdempotentRequestsTests).
 */
class ScheduledJobsTests {

    @Test
    void theApiRunsThemWhileTheWorkerGroupHasNoMachine() throws IOException {
        assertThat(jobsEnabled(FlyToml.arguments("api"))).isTrue();
    }

    @Test
    void theWorkerRunsThem() throws IOException {
        assertThat(jobsEnabled(FlyToml.arguments("worker"))).isTrue();
    }

    /** Its JVM must exit once the migrations are applied (ADR-0009). */
    @Test
    void theReleaseCommandNeverRunsThem() throws IOException {
        assertThat(jobsEnabled(FlyToml.arguments("release_command"))).isFalse();
    }

    @Test
    void locallyNoProcessRunsThem() {
        assertThat(jobsEnabled(List.of())).isFalse();
    }

    private static boolean jobsEnabled(List<String> arguments) {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new SimpleCommandLinePropertySource(arguments.toArray(String[]::new)));
        // What SpringApplication does with the config files once the command line is known:
        // spring.profiles.active picks the application-<profile>.yml files, the last profile winning.
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment.getProperty("moneytrail.jobs.enabled", Boolean.class, false);
    }
}
