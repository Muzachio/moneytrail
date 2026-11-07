package io.github.muzachio.moneytrail.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The production layout (ADR-0007): the app on https://app.example.com, the API on
 * https://api.example.com behind Fly.io's HTTPS proxy. The properties are the ones
 * application-prod.yml fills from MONEYTRAIL_APP_URL and MONEYTRAIL_COOKIE_DOMAIN.
 */
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "moneytrail.web.app-url=https://app.example.com",
            "moneytrail.web.csrf-cookie-domain=example.com",
            "server.forward-headers-strategy=framework"
        })
class CrossSiteTests extends PostgresIntegrationTest {

    private static final String APP = "https://app.example.com";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void theAppMayCallTheApiWithTheSessionCookie() {
        MvcTestResult preflight = mvc.options()
                .uri("/logout")
                .header(HttpHeaders.ORIGIN, APP)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-xsrf-token")
                .exchange();

        assertThat(preflight).hasStatusOk();
        assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo(APP);
        assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .isEqualTo("true");
        assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                .containsIgnoringCase("x-xsrf-token");
    }

    @Test
    void otherSitesMayNot() {
        MvcTestResult preflight = mvc.options()
                .uri("/v1/me")
                .header(HttpHeaders.ORIGIN, "https://evil.example.org")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange();

        assertThat(preflight).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(preflight.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isNull();
    }

    @Test
    void googleSendsPeopleBackOverHttpsBehindTheProxy() {
        MvcTestResult result = mvc.get()
                .uri("/oauth2/authorization/google")
                .header(HttpHeaders.HOST, "api.example.com")
                .header("X-Forwarded-Proto", "https")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        String location = Objects.requireNonNull(result.getResponse().getHeader(HttpHeaders.LOCATION));
        assertThat(UriComponentsBuilder.fromUriString(location)
                        .build()
                        .getQueryParams()
                        .getFirst("redirect_uri"))
                .isEqualTo("https://api.example.com/login/oauth2/code/google");
    }

    @Test
    void aFailedSignInReturnsToTheApp() {
        // A callback without a matching sign-in attempt, as if someone replayed an old link.
        MvcTestResult result = mvc.get()
                .uri("/login/oauth2/code/google?code=test-code&state=unknown-state")
                .header(HttpHeaders.HOST, "api.example.com")
                .header("X-Forwarded-Proto", "https")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(APP + "/?sign-in=failed");
    }

    @Test
    void theCsrfCookieIsSharedWithTheAppButTheSessionCookieIsNot() {
        MvcTestResult result = mvc.get()
                .uri("/oauth2/authorization/google")
                .header(HttpHeaders.HOST, "api.example.com")
                .header("X-Forwarded-Proto", "https")
                .exchange();

        List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies)
                .filteredOn(cookie -> cookie.startsWith("XSRF-TOKEN="))
                .singleElement()
                .asString()
                .contains("Domain=example.com", "Secure")
                .doesNotContain("HttpOnly");
        assertThat(cookies)
                .filteredOn(cookie -> cookie.startsWith("__Host-SESSION="))
                .singleElement()
                .asString()
                .doesNotContainIgnoringCase("Domain=");
    }
}
