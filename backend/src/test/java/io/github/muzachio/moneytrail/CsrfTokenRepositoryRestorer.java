package io.github.muzachio.moneytrail;

import java.util.Optional;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.web.support.WebTestUtils;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.web.context.WebApplicationContext;

/**
 * Puts back, after each test method, the CSRF token repository that Spring Security's {@code csrf()}
 * test post-processor swaps out.
 *
 * <p>{@code csrf()} replaces the repository of the {@code CsrfFilter} in the application context with
 * a {@code TestCsrfTokenRepository} around a new {@code HttpSessionCsrfTokenRepository}, and never
 * puts the app's {@code CookieCsrfTokenRepository} back. The tests share one context, so every later
 * request without {@code csrf()} kept its CSRF token in an HTTP session instead of the {@code
 * XSRF-TOKEN} cookie: an anonymous request created a session, and the cookie was never sent. {@code
 * SignInTests}, which checks both, then passed or failed depending on whether a test using {@code
 * csrf()} had run before it.
 *
 * <p>Registered for every Spring test in {@code META-INF/spring.factories}, next to the default
 * listeners. A test without a web application context or a CSRF filter is left as it is.
 */
public final class CsrfTokenRepositoryRestorer extends AbstractTestExecutionListener {

    private static final String ORIGINAL = CsrfTokenRepositoryRestorer.class.getName() + ".original";

    @Override
    public void beforeTestMethod(TestContext testContext) {
        filterRequest(testContext)
                .ifPresent(request -> testContext.setAttribute(ORIGINAL, WebTestUtils.getCsrfTokenRepository(request)));
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        if (testContext.removeAttribute(ORIGINAL) instanceof CsrfTokenRepository original) {
            filterRequest(testContext).ifPresent(request -> WebTestUtils.setCsrfTokenRepository(request, original));
        }
    }

    /**
     * A request in the test's servlet context, which is how {@link WebTestUtils}, like {@code csrf()}
     * itself, finds the context's security filters.
     */
    private static Optional<MockHttpServletRequest> filterRequest(TestContext testContext) {
        if (!testContext.hasApplicationContext()) {
            return Optional.empty();
        }
        ApplicationContext context = testContext.getApplicationContext();
        if (context instanceof WebApplicationContext web && web.getServletContext() != null) {
            return Optional.of(new MockHttpServletRequest(web.getServletContext()));
        }
        return Optional.empty();
    }
}
