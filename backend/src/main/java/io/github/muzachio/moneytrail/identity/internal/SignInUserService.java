package io.github.muzachio.moneytrail.identity.internal;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/** Runs after Google confirms who signed in: finds or creates the user and returns the principal. */
@Component
class SignInUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcUserService google = new OidcUserService();
    private final Users users;

    SignInUserService(Users users) {
        this.users = users;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser oidcUser = google.loadUser(request);
        SignInProfile profile = SignInProfile.of(request.getClientRegistration().getRegistrationId(), oidcUser);
        return new SignedInUser(users.signIn(profile), oidcUser);
    }
}
