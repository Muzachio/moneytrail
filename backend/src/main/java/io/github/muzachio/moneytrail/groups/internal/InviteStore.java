package io.github.muzachio.moneytrail.groups.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code group_invites} table (V15). A link is active while it is neither revoked nor expired;
 * the database's clock decides both, so every process agrees.
 */
@Repository
class InviteStore {

    record Invite(UUID id, UUID groupId, UUID createdBy, Instant createdAt, Instant expiresAt) {}

    private static final String ACTIVE = "revoked_at IS NULL AND expires_at > now()";

    private final JdbcClient jdbc;

    InviteStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Valid for 7 days from now (the column's default, V15). */
    Invite insert(UUID groupId, byte[] tokenHash, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO group_invites (group_id, token_hash, created_by)
                        VALUES (:groupId, :tokenHash, :createdBy)
                        RETURNING id, group_id, created_by, created_at, expires_at""")
                .param("groupId", groupId)
                .param("tokenHash", tokenHash)
                .param("createdBy", createdBy)
                .query(InviteStore::invite)
                .single();
    }

    /** The group's active links, newest first: ids are UUIDv7. */
    List<Invite> active(UUID groupId) {
        return jdbc.sql("""
                        SELECT id, group_id, created_by, created_at, expires_at
                        FROM group_invites
                        WHERE group_id = :groupId AND %s
                        ORDER BY id DESC""".formatted(ACTIVE))
                .param("groupId", groupId)
                .query(InviteStore::invite)
                .list();
    }

    /** Revokes the group's active link with this id, and says whether there was one. */
    boolean revoke(UUID groupId, UUID inviteId) {
        return jdbc.sql("UPDATE group_invites SET revoked_at = now() WHERE id = :id AND group_id = :groupId AND "
                                + ACTIVE)
                        .param("id", inviteId)
                        .param("groupId", groupId)
                        .update()
                == 1;
    }

    /** The active link with this token's hash, if there is one. */
    Optional<Invite> findActive(byte[] tokenHash) {
        return jdbc.sql("""
                        SELECT id, group_id, created_by, created_at, expires_at
                        FROM group_invites
                        WHERE token_hash = :tokenHash AND %s""".formatted(ACTIVE))
                .param("tokenHash", tokenHash)
                .query(InviteStore::invite)
                .optional();
    }

    private static Invite invite(ResultSet rs, int rowNum) throws SQLException {
        return new Invite(
                rs.getObject("id", UUID.class),
                rs.getObject("group_id", UUID.class),
                rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }
}
