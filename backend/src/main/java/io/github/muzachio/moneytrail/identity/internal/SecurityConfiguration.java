package io.github.muzachio.moneytrail.identity.internal;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Google sign-in with server-side sessions (ADR-0007). Everything requires a session except the
 * health checks. Spring Security 7 adds PKCE, {@code state} and {@code nonce} to the Google
 * redirect by default; SignInTests pins that.
 */
@Configuration(proxyBeanMethods = false)
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
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
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
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/?sign-in=failed"))
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
                // Token in a cookie the SPA can read, sent back as the X-XSRF-TOKEN header.
                .csrf(csrf -> csrf.spa())
                .logout(logout -> logout.logoutSuccessHandler(
                        new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
        return http.build();
    }
}
