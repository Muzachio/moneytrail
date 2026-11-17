package io.github.muzachio.moneytrail.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class SignInFailureHandlerTests {

    private static final String APP = "https://app.example.com";

    @Test
    void someoneWhoIsNotInvitedGoesBackToTheAppWithoutASession() throws IOException {
        MockHttpSession session = new MockHttpSession();

        MockHttpServletResponse response =
                fail(new SignInFailureHandler(APP), session, oauthError(Invitations.NOT_INVITED));

        assertThat(response.getRedirectedUrl()).isEqualTo(APP + "/?sign-in=not-invited");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void otherFailuresSaySignInFailed() throws IOException {
        SignInFailureHandler handler = new SignInFailureHandler(APP);

        assertThat(fail(handler, new MockHttpSession(), oauthError("unverified_email"))
                        .getRedirectedUrl())
                .isEqualTo(APP + "/?sign-in=failed");
        assertThat(fail(handler, null, new BadCredentialsException("test")).getRedirectedUrl())
                .isEqualTo(APP + "/?sign-in=failed");
    }

    @Test
    void withAppAndApiOnOneOriginTheRedirectStaysOnIt() throws IOException {
        MockHttpServletResponse response =
                fail(new SignInFailureHandler(""), null, oauthError(Invitations.NOT_INVITED));

        assertThat(response.getRedirectedUrl()).isEqualTo("/?sign-in=not-invited");
    }

    private static MockHttpServletResponse fail(
            SignInFailureHandler handler, @Nullable MockHttpSession session, AuthenticationException exception)
            throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        if (session != null) {
            request.setSession(session);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(request, response, exception);
        return response;
    }

    private static OAuth2AuthenticationException oauthError(String code) {
        return new OAuth2AuthenticationException(new OAuth2Error(code));
    }
}
