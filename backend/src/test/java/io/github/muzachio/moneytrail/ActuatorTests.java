package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@AutoConfigureMockMvc
class ActuatorTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void healthIsUpWithoutExposingDetails() {
        var body = assertThat(mvc.get().uri("/actuator/health")).hasStatusOk().bodyJson();

        body.extractingPath("$.status").isEqualTo("UP");
        body.doesNotHavePath("$.components");
    }

    @Test
    void livenessAndReadinessProbesAreUp() {
        assertThat(mvc.get().uri("/actuator/health/liveness"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
        assertThat(mvc.get().uri("/actuator/health/readiness"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void sensitiveEndpointsAreNotExposed() {
        assertThat(mvc.get().uri("/actuator/env")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/actuator/beans")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/actuator/configprops")).hasStatus(HttpStatus.NOT_FOUND);
    }
}
