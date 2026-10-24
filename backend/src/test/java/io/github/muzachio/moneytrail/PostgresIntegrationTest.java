package io.github.muzachio.moneytrail;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base class for integration tests that need the database. Tests extending it share one Spring
 * context, and therefore one Postgres container, across the test run.
 */
@SpringBootTest
@Import(PostgresTestcontainersConfiguration.class)
public abstract class PostgresIntegrationTest {}
