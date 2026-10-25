package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@AutoConfigureMockMvc
class ApiEndpointsTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void healthReportsUp() {
        assertThat(mvc.get().uri("/v1/health"))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("UP");
    }

    @Test
    void currentUserWithoutSessionIsUnauthenticatedProblem() {
        var response = assertThat(mvc.get().uri("/v1/me"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson();

        response.extractingPath("$.code").isEqualTo("unauthenticated");
        response.extractingPath("$.status").isEqualTo(401);
    }

    @Test
    void unknownPathIsNotFoundProblemWithCode() {
        assertThat(mvc.get().uri("/v1/does-not-exist"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("not_found");
    }
}
