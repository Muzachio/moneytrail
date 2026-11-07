package io.github.muzachio.moneytrail.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

class UsersTests extends PostgresIntegrationTest {

    @Autowired
    private Users users;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void firstSignInCreatesTheUserAndItsIdentity() {
        SignInProfile profile = SignInProfile.of("google", TestUsers.googleAccount("Ana Teste", true));

        UUID id = users.signIn(profile);

        assertThat(users.find(id)).contains(new Users.User(id, profile.email(), "Ana Teste", profile.avatarUrl()));
        Long identities = jdbc.sql(
                        "SELECT count(*) FROM identities WHERE provider = 'google' AND subject = :subject AND user_id = :id")
                .param("subject", profile.subject())
                .param("id", id)
                .query(Long.class)
                .single();
        assertThat(identities).isEqualTo(1L);
    }

    @Test
    void laterSignInsKeepTheSameUserAndRefreshTheProfile() {
        SignInProfile first = SignInProfile.of("google", TestUsers.googleAccount("Ana Teste", true));
        UUID id = users.signIn(first);
        SignInProfile renamed =
                new SignInProfile(first.provider(), first.subject(), first.email(), "Ana T. Teste", null);

        UUID again = users.signIn(renamed);

        assertThat(again).isEqualTo(id);
        assertThat(users.find(id)).hasValueSatisfying(user -> {
            assertThat(user.name()).isEqualTo("Ana T. Teste");
            assertThat(user.avatarUrl()).isNull();
        });
    }

    @Test
    void differentAccountsBecomeDifferentUsers() {
        UUID ana = users.signIn(SignInProfile.of("google", TestUsers.googleAccount("Ana Teste", true)));
        UUID rui = users.signIn(SignInProfile.of("google", TestUsers.googleAccount("Rui Teste", true)));

        assertThat(ana).isNotEqualTo(rui);
    }

    @Test
    void anEmailBelongsToOneUserWhateverItsCase() {
        SignInProfile ana = SignInProfile.of("google", TestUsers.googleAccount("Ana Teste", true));
        users.signIn(ana);
        SignInProfile other = SignInProfile.of("google", TestUsers.googleAccount("Ana Teste", true));
        SignInProfile sameEmailShouting = new SignInProfile(
                other.provider(), other.subject(), ana.email().toUpperCase(Locale.ROOT), other.name(), null);

        assertThatThrownBy(() -> users.signIn(sameEmailShouting)).isInstanceOf(DuplicateKeyException.class);
    }
}
