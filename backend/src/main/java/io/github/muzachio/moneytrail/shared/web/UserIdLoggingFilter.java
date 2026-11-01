package io.github.muzachio.moneytrail.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds the signed-in user's id to every log line of the request, next to the traceId, so one
 * user's problem can be followed in the logs. Only the internal id is logged, never the email
 * or the name (see CLAUDE.md). Registered with the lowest precedence, so it runs after Spring
 * Security has resolved the principal (issue #8 makes the principal name the internal user id).
 */
@Component
class UserIdLoggingFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Principal principal = request.getUserPrincipal();
        if (principal == null) {
            chain.doFilter(request, response);
            return;
        }
        try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, principal.getName())) {
            chain.doFilter(request, response);
        }
    }
}
