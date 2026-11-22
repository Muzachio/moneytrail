package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.util.Objects;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.web.support.WebTestUtils;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.WebApplicationContext;

/**
 * A test that uses {@code csrf()} leaves the shared context as it found it ({@link
 * CsrfTokenRepositoryRestorer}). The second test runs after the first in the same context, as any
 * later test class would, and finds the app's cookie-based CSRF tokens again. This class sorts before
 * the identity tests, so {@code SignInTests} also runs after a test that used {@code csrf()}.
 */
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CsrfTokenRepositoryRestorerTests extends PostgresIntegrationTest {

    private static final String SESSION_COOKIE = "__Host-SESSION";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private WebApplicationContext context;

    // The reason for the restorer: within a test, csrf() swaps the filter's repository.
    @Test
    @Order(1)
    void csrfSwapsTheAppsTokenRepositoryWithinATest() {
        assertThat(tokenRepository()).isInstanceOf(CookieCsrfTokenRepository.class);

        assertThat(mvc.get().uri("/v1/health").with(csrf())).hasStatusOk();

        assertThat(tokenRepository()).isNotInstanceOf(CookieCsrfTokenRepository.class);
    }

    @Test
    @Order(2)
    void theNextTestHasTheAppsCookieTokensAndNoSessionAgain() {
        assertThat(tokenRepository()).isInstanceOf(CookieCsrfTokenRepository.class);

        MvcTestResult health = mvc.get().uri("/v1/health").exchange();
        MvcTestResult me = mvc.get().uri("/v1/me").exchange();

        assertThat(health).hasStatusOk();
        assertThat(health.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(header -> header.startsWith("XSRF-TOKEN="))
                .noneMatch(header -> header.startsWith(SESSION_COOKIE + "="));
        assertThat(me).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(me.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .noneMatch(header -> header.startsWith(SESSION_COOKIE + "="));
    }

    private CsrfTokenRepository tokenRepository() {
        return Objects.requireNonNull(
                WebTestUtils.getCsrfTokenRepository(new MockHttpServletRequest(context.getServletContext())),
                "no CSRF token repository");
    }
}
