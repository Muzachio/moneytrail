package io.github.muzachio.moneytrail.identity.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Sends a failed sign-in back to the app with a reason it can explain: {@code ?sign-in=not-invited}
 * or {@code ?sign-in=failed}. The reason is a fixed word, never the email, so nothing personal
 * reaches a URL, the browser's history or an access log.
 *
 * <p>The session that carried the sign-in attempt is ended. Spring's default handler would keep it,
 * holding the exception, so a refused person would still leave a session row behind.
 */
final class SignInFailureHandler implements AuthenticationFailureHandler {

    private final String appUrl;
    private final RedirectStrategy redirects = new DefaultRedirectStrategy();

    /** @param appUrl the app's origin; empty when app and API share one (WebProperties). */
    SignInFailureHandler(String appUrl) {
        this.appUrl = appUrl;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        redirects.sendRedirect(request, response, appUrl + "/?sign-in=" + reason(exception));
    }

    private static String reason(AuthenticationException exception) {
        return exception instanceof OAuth2AuthenticationException oauth
                        && Invitations.NOT_INVITED.equals(oauth.getError().getErrorCode())
                ? "not-invited"
                : "failed";
    }
}
