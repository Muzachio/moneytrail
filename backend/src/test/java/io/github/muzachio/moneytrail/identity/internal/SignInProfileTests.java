package io.github.muzachio.moneytrail.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class SignInProfileTests {

    @Test
    void takesSubjectEmailNameAndPictureFromAVerifiedAccount() {
        OidcUser account = TestUsers.googleAccount("Ana Teste", true);

        SignInProfile profile = SignInProfile.of("google", account);

        assertThat(profile.provider()).isEqualTo("google");
        assertThat(profile.subject()).isEqualTo(account.getSubject());
        assertThat(profile.email()).isEqualTo(account.getEmail());
        assertThat(profile.name()).isEqualTo("Ana Teste");
        assertThat(profile.avatarUrl()).isEqualTo(account.getPicture());
    }

    @Test
    void rejectsAnUnverifiedEmail() {
        OidcUser account = TestUsers.googleAccount("Ana Teste", false);

        assertThatThrownBy(() -> SignInProfile.of("google", account))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        e -> assertThat(e.getError().getErrorCode()).isEqualTo("unverified_email"));
    }

    @Test
    void fallsBackToTheEmailWhenTheAccountHasNoName() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("test-id-token")
                .subject("google-subject-without-name")
                .claim("email", "no-name@example.com")
                .claim("email_verified", true)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        OidcUser account = new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);

        SignInProfile profile = SignInProfile.of("google", account);

        assertThat(profile.name()).isEqualTo("no-name@example.com");
        assertThat(profile.avatarUrl()).isNull();
    }
}
