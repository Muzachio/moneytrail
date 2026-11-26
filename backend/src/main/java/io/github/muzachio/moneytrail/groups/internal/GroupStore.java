package io.github.muzachio.moneytrail.groups.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code groups} and {@code group_members} tables (V14), which only this module writes. */
@Repository
class GroupStore {

    record Row(
            UUID id,
            String name,
            Currency currency,
            UUID ledgerId,
            Instant createdAt,
            @Nullable Instant archivedAt) {

        boolean archived() {
            return archivedAt != null;
        }
    }

    /** A group in a user's list, with the user's member account in it. */
    record Listed(Row group, int memberCount, UUID accountId) {}

    record Member(UUID userId, UUID accountId, Instant joinedAt) {}

    /** Someone's row in a group, whether they are a member now or left it. */
    record Membership(UUID accountId, @Nullable Instant leftAt) {

        boolean current() {
            return leftAt == null;
        }
    }

    /**
     * How a read of a group locks its row. Archiving takes {@link #UPDATE} while it checks that every
     * balance is zero; an expense or a payment (#88, #89) takes {@link #SHARE} before posting, so
     * the two wait for each other and a group is never archived with a balance left.
     */
    enum Lock {
        NONE(""),
        SHARE(" FOR SHARE OF g"),
        UPDATE(" FOR UPDATE OF g");

        private final String clause;

        Lock(String clause) {
            this.clause = clause;
        }
    }

    private static final String COLUMNS = "g.id, g.name, g.currency, g.ledger_id, g.created_at, g.archived_at";

    private final JdbcClient jdbc;

    GroupStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A new group's id, taken first because the group's ledger is opened for it before the row exists. */
    UUID newId() {
        return jdbc.sql("SELECT uuidv7()").query(UUID.class).single();
    }

    Row insert(UUID id, String name, Currency currency, UUID ledgerId, UUID createdBy) {
        return jdbc.sql("""
                        INSERT INTO groups AS g (id, name, currency, ledger_id, created_by)
                        VALUES (:id, :name, :currency, :ledgerId, :createdBy)
                        RETURNING %s""".formatted(COLUMNS))
                .param("id", id)
                .param("name", name)
                .param("currency", currency.getCurrencyCode())
                .param("ledgerId", ledgerId)
                .param("createdBy", createdBy)
                .query(GroupStore::row)
                .single();
    }

    /** Adds a member with their account, and returns when they joined. */
    Instant addMember(UUID groupId, UUID userId, UUID accountId) {
        return jdbc.sql("""
                        INSERT INTO group_members (group_id, user_id, account_id)
                        VALUES (:groupId, :userId, :accountId)
                        RETURNING joined_at""")
                .param("groupId", groupId)
                .param("userId", userId)
                .param("accountId", accountId)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    /**
     * Someone who left joins again with the account they had (ADR-0012), so their part in past
     * expenses stays theirs.
     *
     * @return when they joined again
     */
    Instant rejoin(UUID groupId, UUID userId) {
        return jdbc.sql("""
                        UPDATE group_members SET left_at = NULL, joined_at = now()
                        WHERE group_id = :groupId AND user_id = :userId AND left_at IS NOT NULL
                        RETURNING joined_at""")
                .param("groupId", groupId)
                .param("userId", userId)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    Optional<Membership> membership(UUID groupId, UUID userId) {
        return jdbc.sql("SELECT account_id, left_at FROM group_members WHERE group_id = :groupId AND user_id = :userId")
                .param("groupId", groupId)
                .param("userId", userId)
                .query((rs, rowNum) -> {
                    OffsetDateTime leftAt = rs.getObject("left_at", OffsetDateTime.class);
                    return new Membership(
                            rs.getObject("account_id", UUID.class), leftAt == null ? null : leftAt.toInstant());
                })
                .optional();
    }

    /** The group, whoever asks: joining it through an invite needs no membership yet. */
    Optional<Row> find(UUID groupId, Lock lock) {
        return jdbc.sql("SELECT %s FROM groups g WHERE g.id = :groupId%s".formatted(COLUMNS, lock.clause))
                .param("groupId", groupId)
                .query(GroupStore::row)
                .optional();
    }

    int memberCount(UUID groupId) {
        return jdbc.sql("SELECT count(*) FROM group_members WHERE group_id = :groupId AND left_at IS NULL")
                .param("groupId", groupId)
                .query(Integer.class)
                .single();
    }

    /** The group, if {@code userId} is a member of it now: not for someone who left, nor for anyone else. */
    Optional<Row> findForMember(UUID groupId, UUID userId, Lock lock) {
        return jdbc.sql("""
                        SELECT %s
                        FROM groups g
                        JOIN group_members m ON m.group_id = g.id
                        WHERE g.id = :groupId AND m.user_id = :userId AND m.left_at IS NULL%s""".formatted(COLUMNS, lock.clause))
                .param("groupId", groupId)
                .param("userId", userId)
                .query(GroupStore::row)
                .optional();
    }

    /**
     * The member's row, locked until the transaction ends. Leaving holds it while it checks the
     * balance, and an expense or a payment (#88, #89) locks the rows of the members it posts to {@code
     * FOR SHARE}, so nobody leaves with a balance that a posting is about to change.
     */
    Optional<Member> lockMembership(UUID groupId, UUID userId) {
        return jdbc.sql("""
                        SELECT user_id, account_id, joined_at
                        FROM group_members
                        WHERE group_id = :groupId AND user_id = :userId AND left_at IS NULL
                        FOR UPDATE""")
                .param("groupId", groupId)
                .param("userId", userId)
                .query(GroupStore::member)
                .optional();
    }

    /** The user's groups, in the order they were created: ids are UUIDv7. */
    List<Listed> listForUser(UUID userId) {
        return jdbc.sql("""
                        SELECT %s, m.account_id,
                               (SELECT count(*) FROM group_members c
                                WHERE c.group_id = g.id AND c.left_at IS NULL) AS member_count
                        FROM groups g
                        JOIN group_members m ON m.group_id = g.id
                        WHERE m.user_id = :userId AND m.left_at IS NULL
                        ORDER BY g.id""".formatted(COLUMNS))
                .param("userId", userId)
                .query((rs, rowNum) ->
                        new Listed(row(rs, rowNum), rs.getInt("member_count"), rs.getObject("account_id", UUID.class)))
                .list();
    }

    /**
     * The owner of every member account of the group, by account: members who left included, since
     * their account and their part in past expenses and settlements stay (ADR-0012).
     */
    Map<UUID, UUID> accountOwners(UUID groupId) {
        Map<UUID, UUID> owners = new HashMap<>();
        jdbc.sql("SELECT account_id, user_id FROM group_members WHERE group_id = :groupId")
                .param("groupId", groupId)
                .query(rs -> {
                    owners.put(rs.getObject("account_id", UUID.class), rs.getObject("user_id", UUID.class));
                });
        return owners;
    }

    /**
     * The member account of each of {@code userIds} who is a member of the group now, by user; the
     * others are not in the map. Their rows stay locked {@code FOR SHARE} until the transaction ends,
     * so none of them leaves while an expense or a settlement changes their balance: leaving locks its
     * row {@code FOR UPDATE} and checks the balance (see {@link #lockMembership}). In the order of the
     * ids, like every lock taken here.
     */
    Map<UUID, UUID> lockCurrentMembers(UUID groupId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> accounts = new HashMap<>();
        jdbc.sql("""
                        SELECT user_id, account_id
                        FROM group_members
                        WHERE group_id = :groupId AND user_id IN (:userIds) AND left_at IS NULL
                        ORDER BY user_id
                        FOR SHARE""").param("groupId", groupId).param("userIds", userIds).query(rs -> {
            accounts.put(rs.getObject("user_id", UUID.class), rs.getObject("account_id", UUID.class));
        });
        return accounts;
    }

    /** The current members, in the order they joined. */
    List<Member> members(UUID groupId) {
        return jdbc.sql("""
                        SELECT user_id, account_id, joined_at
                        FROM group_members
                        WHERE group_id = :groupId AND left_at IS NULL
                        ORDER BY joined_at, user_id""").param("groupId", groupId).query(GroupStore::member).list();
    }

    Row rename(UUID groupId, String name) {
        return update("name = :name", groupId, name);
    }

    /** Archiving an archived group keeps the time it was first archived. */
    Row archive(UUID groupId) {
        return update("archived_at = coalesce(archived_at, now())", groupId, null);
    }

    Row restore(UUID groupId) {
        return update("archived_at = NULL", groupId, null);
    }

    Instant markLeft(UUID groupId, UUID userId) {
        return jdbc.sql("""
                        UPDATE group_members SET left_at = now()
                        WHERE group_id = :groupId AND user_id = :userId AND left_at IS NULL
                        RETURNING left_at""")
                .param("groupId", groupId)
                .param("userId", userId)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private Row update(String assignment, UUID groupId, @Nullable String name) {
        var statement = jdbc.sql(
                        "UPDATE groups AS g SET %s WHERE g.id = :groupId RETURNING %s".formatted(assignment, COLUMNS))
                .param("groupId", groupId);
        if (name != null) {
            statement = statement.param("name", name);
        }
        return statement.query(GroupStore::row).single();
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime archivedAt = rs.getObject("archived_at", OffsetDateTime.class);
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                Currency.getInstance(rs.getString("currency")),
                rs.getObject("ledger_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                archivedAt == null ? null : archivedAt.toInstant());
    }

    private static Member member(ResultSet rs, int rowNum) throws SQLException {
        return new Member(
                rs.getObject("user_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getObject("joined_at", OffsetDateTime.class).toInstant());
    }
}
