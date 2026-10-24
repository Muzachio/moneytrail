package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class FlywayMigrationsTests extends PostgresIntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void appliesBaselineMigration() {
        Boolean succeeded = jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '1'")
                .query(Boolean.class)
                .single();

        assertThat(succeeded).isTrue();
    }

    @Test
    void enablesCitextExtension() {
        Long extensions = jdbc.sql("SELECT count(*) FROM pg_extension WHERE extname = 'citext'")
                .query(Long.class)
                .single();

        assertThat(extensions).isEqualTo(1L);
    }
}
