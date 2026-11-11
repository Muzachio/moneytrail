package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.WebApplicationContext;

/** The worker process group in backend/fly.toml: same code, no HTTP server (application-worker.yml). */
class WorkerProcessTests extends WorkerIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsWithoutAWebServerOrSecurityFilters() {
        assertThat(context).isNotInstanceOf(WebApplicationContext.class);
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).isEmpty();
    }
}
