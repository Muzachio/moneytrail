package io.github.muzachio.moneytrail.identity.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Invented Google accounts for tests; each call gets a fresh subject and email. */
final class TestUsers {

    private TestUsers() {}

    static OidcUser googleAccount(String name, boolean emailVerified) {
        String suffix = UUID.randomUUID().toString();
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-id-token")
                .subject("google-subject-" + suffix)
                .claim("email", "test-" + suffix + "@example.com")
                .claim("email_verified", emailVerified)
                .claim("name", name)
                .claim("picture", "https://example.com/avatar/" + suffix + ".png")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);
    }

    static OAuth2AuthenticationToken signedIn(UUID userId, OidcUser account) {
        SignedInUser principal = new SignedInUser(userId, account);
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }
}
