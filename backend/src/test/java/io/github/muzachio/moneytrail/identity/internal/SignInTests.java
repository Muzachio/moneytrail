package io.github.muzachio.moneytrail.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/** The sign-in flow up to Google, and what a session cookie allows afterwards (ADR-0007). */
@AutoConfigureMockMvc
class SignInTests extends PostgresIntegrationTest {

    private static final String SESSION_COOKIE = "__Host-SESSION";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private Users users;

    @Autowired
    private JdbcIndexedSessionRepository sessions;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void signInRedirectsToGoogleWithPkceStateAndNonce() {
        MvcTestResult result = mvc.get().uri("/oauth2/authorization/google").exchange();

        assertThat(result).hasStatus(HttpStatus.FOUND);
        String location = Objects.requireNonNull(
                result.getResponse().getHeader(HttpHeaders.LOCATION), "redirect without a Location header");
        assertThat(location).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        MultiValueMap<String, String> query =
                UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(query.getFirst("response_type")).isEqualTo("code");
        assertThat(query.getFirst("scope")).contains("openid");
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(query.getFirst("code_challenge")).isNotBlank();
        assertThat(query.getFirst("state")).isNotBlank();
        assertThat(query.getFirst("nonce")).isNotBlank();
    }

    @Test
    void startingSignInStoresTheSessionInPostgresBehindAHostCookie() {
        MvcTestResult result = mvc.get().uri("/oauth2/authorization/google").exchange();

        String setCookie = setCookieHeader(result, SESSION_COOKIE);
        assertThat(setCookie)
                .contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax")
                .doesNotContainIgnoringCase("Domain=");
        String sessionId = new String(Base64.getDecoder().decode(cookieValue(setCookie)), StandardCharsets.UTF_8);
        assertThat(jdbc.sql("SELECT count(*) FROM spring_session WHERE session_id = :id")
                        .param("id", sessionId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void anonymousApiCallsCreateNoSession() {
        MvcTestResult result = mvc.get().uri("/v1/me").exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .noneMatch(header -> header.startsWith(SESSION_COOKIE + "="));
    }

    @Test
    void sessionCookieSignsTheUserIn() {
        OidcUser account = TestUsers.googleAccount("Ana Teste", true);
        UUID userId = users.signIn(SignInProfile.of("google", account));
        Cookie session = sessionCookieFor(userId, account);

        var body = assertThat(mvc.get().uri("/v1/me").cookie(session))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson();

        body.extractingPath("$.id").isEqualTo(userId.toString());
        body.extractingPath("$.email").isEqualTo(account.getEmail());
        body.extractingPath("$.name").isEqualTo("Ana Teste");
        body.extractingPath("$.avatarUrl").isEqualTo(account.getPicture());
    }

    @Test
    void signOutNeedsTheCsrfTokenFromTheCookieAndEndsTheSession() {
        OidcUser account = TestUsers.googleAccount("Ana Teste", true);
        UUID userId = users.signIn(SignInProfile.of("google", account));
        Cookie session = sessionCookieFor(userId, account);

        assertThat(mvc.post().uri("/logout").cookie(session))
                .hasStatus(HttpStatus.FORBIDDEN)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("forbidden");

        // What the SPA does: read the XSRF-TOKEN cookie and send it back as a header.
        String xsrfCookie = setCookieHeader(mvc.get().uri("/v1/health").exchange(), "XSRF-TOKEN");
        assertThat(xsrfCookie).doesNotContain("HttpOnly");
        String token = cookieValue(xsrfCookie);
        assertThat(mvc.post()
                        .uri("/logout")
                        .cookie(session, new Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(mvc.get().uri("/v1/me").cookie(session)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    /** A session as Spring Security leaves it after a successful Google sign-in. */
    private Cookie sessionCookieFor(UUID userId, OidcUser account) {
        String sessionId = saveSession(sessions, new SecurityContextImpl(TestUsers.signedIn(userId, account)));
        String value = Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
        return new Cookie(SESSION_COOKIE, value);
    }

    // Generic so the code only names the public Session type, not JdbcSession, which is not public.
    private static <S extends Session> String saveSession(SessionRepository<S> repository, SecurityContext context) {
        S session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
        return session.getId();
    }

    private static String setCookieHeader(MvcTestResult result, String name) {
        List<String> headers = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        return headers.stream()
                .filter(header -> header.startsWith(name + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + name + " cookie in " + headers));
    }

    private static String cookieValue(String setCookieHeader) {
        String nameAndValue = setCookieHeader.split(";", 2)[0];
        return nameAndValue.substring(nameAndValue.indexOf('=') + 1);
    }
}
