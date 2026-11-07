package io.github.muzachio.moneytrail.identity.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the SPA lives relative to the API. Locally both are empty: Vite serves the app and proxies
 * the API on one origin. In production the app and the API are subdomains of one domain
 * (ADR-0007), e.g. {@code https://app.example.com} and {@code https://api.example.com}.
 *
 * @param appUrl the app's origin (scheme, host and port, no path); sign-in returns there and only
 *     that origin may call the API from a browser. Empty means "same origin as the API".
 * @param csrfCookieDomain the domain the CSRF cookie is shared with, so the app's JavaScript can
 *     read a cookie the API set (e.g. {@code example.com}); empty keeps it on the API's host.
 */
@ConfigurationProperties("moneytrail.web")
record WebProperties(
        @DefaultValue("") String appUrl, @Nullable String csrfCookieDomain) {

    WebProperties {
        appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
        csrfCookieDomain = csrfCookieDomain == null || csrfCookieDomain.isBlank() ? null : csrfCookieDomain;
    }

    boolean crossOrigin() {
        return !appUrl.isEmpty();
    }
}
