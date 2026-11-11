package io.github.muzachio.moneytrail;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for tests of the worker process group in backend/fly.toml: same code, no HTTP server
 * (application-worker.yml), and the scheduled jobs. Tests extending it share one Spring context
 * and one Postgres container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@ActiveProfiles("worker")
public abstract class WorkerIntegrationTest {}
