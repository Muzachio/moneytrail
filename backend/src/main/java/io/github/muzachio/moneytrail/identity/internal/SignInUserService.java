package io.github.muzachio.moneytrail.identity.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/** Runs after Google confirms who signed in: finds or creates the user and returns the principal. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SignInUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private static final Logger log = LoggerFactory.getLogger(SignInUserService.class);

    private final OidcUserService google = new OidcUserService();
    private final Users users;
    private final Invitations invitations;

    SignInUserService(Users users, Invitations invitations) {
        this.users = users;
        this.invitations = invitations;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        return signIn(request.getClientRegistration().getRegistrationId(), google.loadUser(request));
    }

    /**
     * The account as the provider confirmed it. Someone who is not invited is refused before
     * anything is written: no user, no ledger, and SignInFailureHandler ends the session.
     */
    SignedInUser signIn(String provider, OidcUser account) {
        SignInProfile profile = SignInProfile.of(provider, account);
        if (!invitations.admits(profile.email())) {
            // Neither the email nor the subject: the line only says that someone tried.
            log.info("Sign-in refused: the account is not invited");
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(Invitations.NOT_INVITED, "The account is not invited.", null));
        }
        return new SignedInUser(users.signIn(profile), account);
    }
}
