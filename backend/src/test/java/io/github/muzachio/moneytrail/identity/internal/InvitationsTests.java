package io.github.muzachio.moneytrail.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/** Who may sign in (#51). Every email here is invented, on example.com. */
class InvitationsTests {

    @Test
    void anyoneMaySignInUnlessTheAppIsInviteOnly() {
        Invitations open = new Invitations(new SignInProperties(false, List.of()));

        assertThat(open.admits("someone@example.com")).isTrue();
    }

    @Test
    void anInviteOnlyAppAdmitsTheInvitedEmailsIgnoringCase() {
        Invitations invitations =
                new Invitations(new SignInProperties(true, List.of(" Ana@Example.com ", "rui@example.com")));

        assertThat(invitations.admits("ana@example.com")).isTrue();
        assertThat(invitations.admits("ANA@EXAMPLE.COM")).isTrue();
        assertThat(invitations.admits("Rui@Example.com")).isTrue();
        assertThat(invitations.admits("joao@example.com")).isFalse();
        assertThat(invitations.admits("ana@example.org")).isFalse();
    }

    @Test
    void anInviteOnlyAppWithAnEmptyListAdmitsNobody() {
        assertThat(new Invitations(new SignInProperties(true, List.of())).admits("ana@example.com"))
                .isFalse();
        assertThat(new Invitations(new SignInProperties(true, List.of("", " "))).admits(""))
                .isFalse();
    }

    @Test
    void locallyTheAppIsOpen() {
        SignInProperties local = bind(new MutablePropertySources());

        assertThat(local.inviteOnly()).isFalse();
        assertThat(local.invitedEmails()).isEmpty();
    }

    @Test
    void productionReadsTheListFromTheSecret() throws IOException {
        Invitations production = new Invitations(
                bind(production(Map.of("MONEYTRAIL_INVITED_EMAILS", "ana@example.com, Rui@Example.com"))));

        assertThat(production.admits("ana@example.com")).isTrue();
        assertThat(production.admits("rui@example.com")).isTrue();
        assertThat(production.admits("joao@example.com")).isFalse();
    }

    @Test
    void productionWithoutTheSecretAdmitsNobody() throws IOException {
        SignInProperties properties = bind(production(Map.of()));

        assertThat(properties.inviteOnly()).isTrue();
        assertThat(new Invitations(properties).admits("ana@example.com")).isFalse();
    }

    @Test
    void productionWithAnEmptySecretAdmitsNobody() throws IOException {
        Invitations production = new Invitations(bind(production(Map.of("MONEYTRAIL_INVITED_EMAILS", ""))));

        assertThat(production.admits("ana@example.com")).isFalse();
    }

    /** application-prod.yml under the given environment, as Fly.io starts the app. */
    private static MutablePropertySources production(Map<String, Object> environment) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("environment", environment));
        new YamlPropertySourceLoader()
                .load("application-prod.yml", new ClassPathResource("application-prod.yml"))
                .forEach(sources::addLast);
        return sources;
    }

    /** What Spring Boot binds to SignInProperties from these sources, defaults included. */
    private static SignInProperties bind(MutablePropertySources sources) {
        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        return binder.bindOrCreate("moneytrail.sign-in", SignInProperties.class);
    }
}
