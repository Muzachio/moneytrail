package io.github.muzachio.moneytrail.identity.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Google sign-in with server-side sessions (ADR-0007). Everything requires a session except the
 * health checks. Spring Security 7 adds PKCE, {@code state} and {@code nonce} to the Google
 * redirect by default; SignInTests pins that.
 */
@Configuration(proxyBeanMethods = false)
// The worker process (application-worker.yml) has no HTTP server, so nothing here applies to it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(WebProperties.class)
class SecurityConfiguration {

    /**
     * The session cookie. __Host- makes the browser reject it unless it is Secure, has Path=/ and
     * no Domain, so no other subdomain can set or read it. Set here rather than through
     * {@code server.servlet.session.cookie.*}, which Spring Boot only applies with an embedded
     * server: the tests' mock servlet context would otherwise get a plain {@code SESSION} cookie.
     */
    @Bean
    DefaultCookieSerializerCustomizer sessionCookie() {
        return cookie -> {
            cookie.setCookieName("__Host-SESSION");
            cookie.setCookiePath("/");
            cookie.setUseHttpOnlyCookie(true);
            cookie.setUseSecureCookie(true);
            cookie.setSameSite("Lax");
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SignInUserService signInUserService,
            WebProperties web,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        // Token in a cookie the SPA can read, sent back as the X-XSRF-TOKEN header. In production
        // the API sets it but the app reads it, so it is shared with the parent domain. The
        // session cookie stays __Host- (API host only); this token is no secret from our own
        // subdomains, it only has to be unreadable for other sites.
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        String csrfCookieDomain = web.csrfCookieDomain();
        if (csrfCookieDomain != null) {
            csrfTokens.setCookieCustomizer(cookie -> cookie.domain(csrfCookieDomain));
        }

        http.authorizeHttpRequests(requests -> requests.requestMatchers(
                                "/v1/health", "/actuator/health", "/actuator/health/**", "/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2Login(login -> login
                        // The SPA shows the sign-in button; this also turns off Spring's generated
                        // login page.
                        .loginPage("/")
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(signInUserService))
                        // Back to the app, not to the API's own "/" (empty app URL: same origin).
                        .defaultSuccessUrl(web.appUrl() + "/", true)
                        .failureUrl(web.appUrl() + "/?sign-in=failed"))
                .cors(cors -> cors.configurationSource(corsFor(web)))
                // The request cache saves a refused request in a new session, to resume it after
                // sign-in. Sign-in always lands on "/", so it would only add a session row for
                // every anonymous call.
                .requestCache(cache -> cache.disable())
                // An API answers 401/403 with a problem detail instead of redirecting to Google;
                // the exceptions go through ProblemDetailsAdvice like any other error.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> exceptionResolver.resolveException(
                                request, response, null, new UnauthenticatedException()))
                        .accessDeniedHandler((request, response, exception) ->
                                exceptionResolver.resolveException(request, response, null, new ForbiddenException())))
                // spa() also installs the handler that writes the token on every response.
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokens))
                .logout(logout -> logout.logoutSuccessHandler(
                        new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
        return http.build();
    }

    /**
     * Lets the app's origin, and only it, call the API with the session cookie. Nothing is
     * registered when app and API share an origin (local development), so browsers keep refusing
     * every cross-origin call.
     */
    private static CorsConfigurationSource corsFor(WebProperties web) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (web.crossOrigin()) {
            CorsConfiguration app = new CorsConfiguration();
            app.setAllowedOrigins(List.of(web.appUrl()));
            app.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
            app.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key"));
            app.setAllowCredentials(true);
            // Browsers may reuse a preflight answer for an hour instead of asking before each call.
            app.setMaxAge(Duration.ofHours(1));
            source.registerCorsConfiguration("/v1/**", app);
            source.registerCorsConfiguration("/logout", app);
        }
        return source;
    }
}
