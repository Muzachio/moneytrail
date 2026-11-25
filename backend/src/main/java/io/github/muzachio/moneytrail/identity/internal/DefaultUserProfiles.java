package io.github.muzachio.moneytrail.identity.internal;

import static java.util.stream.Collectors.toMap;

import io.github.muzachio.moneytrail.identity.UserProfile;
import io.github.muzachio.moneytrail.identity.UserProfiles;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Reads only the columns a profile shows, so the email never leaves this module this way. */
@Service
class DefaultUserProfiles implements UserProfiles {

    private final JdbcClient jdbc;

    DefaultUserProfiles(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, UserProfile> find(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("SELECT id, name, avatar_url FROM users WHERE id = ANY(:ids)")
                .param("ids", userIds.toArray(UUID[]::new))
                .query((rs, rowNum) -> new UserProfile(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("avatar_url")))
                .stream()
                .collect(toMap(UserProfile::id, Function.identity()));
    }
}
