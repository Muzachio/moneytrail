package io.github.muzachio.moneytrail.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * An invite-only sign-in (#51), from the account Google confirmed to the database. Someone who is
 * not invited leaves nothing behind: no user, no identity, no ledger.
 */
class InvitedSignInTests extends PostgresIntegrationTest {

    @Autowired
    private Users users;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void anInvitedAccountSignsInWhateverTheCaseOfItsEmail() {
        String invited = TestUsers.inventedEmail();
        OidcUser account = TestUsers.googleAccount("Ana Teste", invited.toUpperCase(Locale.ROOT), true);

        SignedInUser signedIn = inviting(invited).signIn("google", account);

        assertThat(users.find(signedIn.userId())).isPresent();
        assertThat(ledgersOf(account)).isEqualTo(1L);
    }

    @Test
    void someoneWhoIsNotInvitedIsRefusedBeforeAnythingIsStored() {
        OidcUser stranger = TestUsers.googleAccount("Rui Teste", true);

        assertThatThrownBy(() -> inviting(TestUsers.inventedEmail()).signIn("google", stranger))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        e -> assertThat(e.getError().getErrorCode()).isEqualTo(Invitations.NOT_INVITED));

        assertThat(usersWithEmailOf(stranger)).isZero();
        assertThat(identitiesOf(stranger)).isZero();
    }

    @Test
    void anInvitedEmailThatGoogleHasNotVerifiedIsStillRefused() {
        String invited = TestUsers.inventedEmail();
        OidcUser unverified = TestUsers.googleAccount("Ana Teste", invited, false);

        assertThatThrownBy(() -> inviting(invited).signIn("google", unverified))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        e -> assertThat(e.getError().getErrorCode()).isEqualTo("unverified_email"));

        assertThat(usersWithEmailOf(unverified)).isZero();
    }

    /** The sign-in as production runs it, with only this email invited. */
    private SignInUserService inviting(String email) {
        return new SignInUserService(users, new Invitations(new SignInProperties(true, List.of(email))));
    }

    private long usersWithEmailOf(OidcUser account) {
        return jdbc.sql("SELECT count(*) FROM users WHERE email = :email")
                .param("email", account.getEmail())
                .query(Long.class)
                .single();
    }

    private long identitiesOf(OidcUser account) {
        return jdbc.sql("SELECT count(*) FROM identities WHERE provider = 'google' AND subject = :subject")
                .param("subject", account.getSubject())
                .query(Long.class)
                .single();
    }

    private long ledgersOf(OidcUser account) {
        return jdbc.sql("""
                        SELECT count(*) FROM ledgers l
                        JOIN identities i ON i.user_id = l.owner_id
                        WHERE l.owner_type = 'USER' AND i.provider = 'google' AND i.subject = :subject
                        """)
                .param("subject", account.getSubject())
                .query(Long.class)
                .single();
    }
}
