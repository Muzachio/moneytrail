package io.github.muzachio.moneytrail.identity.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** What a provider tells us about the person signing in. */
record SignInProfile(
        String provider,
        String subject,
        String email,
        String name,
        @Nullable String avatarUrl) {

    /**
     * Only verified emails are accepted: {@code users.email} is unique, so an unverified address
     * could claim someone else's email.
     */
    static SignInProfile of(String provider, OidcUser user) {
        String subject = user.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("missing_subject", "The provider did not identify the account.", null));
        }
        String email = user.getEmail();
        if (email == null || !Boolean.TRUE.equals(user.getEmailVerified())) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("unverified_email", "The account has no verified email.", null));
        }
        String name = user.getFullName();
        return new SignInProfile(
                provider, subject, email, name == null || name.isBlank() ? email : name, user.getPicture());
    }
}
