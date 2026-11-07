package io.github.muzachio.moneytrail.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class Users {

    record User(
            UUID id, String email, String name, @Nullable String avatarUrl) {}

    private final JdbcClient jdbc;

    Users(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the id of the user behind this provider identity, creating the user on the first
     * sign-in. Later sign-ins refresh email, name and picture, which the person may change at
     * Google.
     */
    @Transactional
    UUID signIn(SignInProfile profile) {
        Optional<UUID> existing = jdbc.sql(
                        "SELECT user_id FROM identities WHERE provider = :provider AND subject = :subject")
                .param("provider", profile.provider())
                .param("subject", profile.subject())
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            jdbc.sql("UPDATE users SET email = :email, name = :name, avatar_url = :avatarUrl WHERE id = :id")
                    .param("email", profile.email())
                    .param("name", profile.name())
                    .param("avatarUrl", profile.avatarUrl())
                    .param("id", existing.get())
                    .update();
            return existing.get();
        }
        UUID id = jdbc.sql(
                        "INSERT INTO users (email, name, avatar_url) VALUES (:email, :name, :avatarUrl) RETURNING id")
                .param("email", profile.email())
                .param("name", profile.name())
                .param("avatarUrl", profile.avatarUrl())
                .query(UUID.class)
                .single();
        jdbc.sql("INSERT INTO identities (provider, subject, user_id) VALUES (:provider, :subject, :userId)")
                .param("provider", profile.provider())
                .param("subject", profile.subject())
                .param("userId", id)
                .update();
        return id;
    }

    Optional<User> find(UUID id) {
        return jdbc.sql("SELECT id, email, name, avatar_url FROM users WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> new User(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("name"),
                        rs.getString("avatar_url")))
                .optional();
    }
}
