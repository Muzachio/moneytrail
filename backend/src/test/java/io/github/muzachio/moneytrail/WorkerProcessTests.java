package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

/** The worker process group in backend/fly.toml: same code, no HTTP server (application-worker.yml). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@ActiveProfiles("worker")
class WorkerProcessTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsWithoutAWebServerOrSecurityFilters() {
        assertThat(context).isNotInstanceOf(WebApplicationContext.class);
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).isEmpty();
    }
}
