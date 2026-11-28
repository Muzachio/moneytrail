package io.github.muzachio.moneytrail.personal.internal;

import static java.util.stream.Collectors.toMap;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code group_mirrors} and {@code group_mirror_entries} tables (V21), which only this module writes. */
@Repository
class GroupMirrorStore {

    /** What a group entry is in the group, as the events say it. */
    enum EntryType {
        EXPENSE,
        SETTLEMENT
    }

    /** A person's mirror of one group, on or off. */
    record Mirror(
            UUID userId,
            UUID groupId,
            UUID memberAccountId,
            UUID accountId,
            UUID categoryId,
            UUID paymentAccountId,
            boolean enabled) {

        Mirror with(UUID categoryId, UUID paymentAccountId, boolean enabled) {
            return new Mirror(userId, groupId, memberAccountId, accountId, categoryId, paymentAccountId, enabled);
        }
    }

    /** How the mirror handled one group entry for one person. */
    record Handled(
            UUID groupEntryId,
            UUID userId,
            UUID groupId,
            @Nullable EntryType type,
            @Nullable UUID personalEntryId,
            boolean beforeMirror) {}

    private static final String MIRROR_COLUMNS =
            "user_id, group_id, member_account_id, account_id, category_id, payment_account_id, enabled";

    private static final String HANDLED_COLUMNS =
            "group_entry_id, user_id, group_id, type, personal_entry_id, before_mirror";

    private final JdbcClient jdbc;

    GroupMirrorStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Mirror> find(UUID userId, UUID groupId) {
        return jdbc.sql("SELECT " + MIRROR_COLUMNS
                        + " FROM group_mirrors WHERE user_id = :userId AND group_id = :groupId")
                .param("userId", userId)
                .param("groupId", groupId)
                .query(GroupMirrorStore::mirror)
                .optional();
    }

    /**
     * The person's mirror of the group, locked until the transaction ends. Turning it on or off, and
     * changing an entry's account, take this lock, as does the listener before it records anything
     * for them, so none of them acts on a mirror another is changing.
     */
    Optional<Mirror> lock(UUID userId, UUID groupId) {
        return jdbc.sql("SELECT " + MIRROR_COLUMNS
                        + " FROM group_mirrors WHERE user_id = :userId AND group_id = :groupId FOR UPDATE")
                .param("userId", userId)
                .param("groupId", groupId)
                .query(GroupMirrorStore::mirror)
                .optional();
    }

    /**
     * The group's mirrors that are on, locked as {@link #lock} does, in the order of their users, so
     * that two listeners locking the same rows never wait for each other in a circle.
     */
    List<Mirror> lockEnabledIn(UUID groupId) {
        return jdbc.sql("SELECT " + MIRROR_COLUMNS
                        + " FROM group_mirrors WHERE group_id = :groupId AND enabled ORDER BY user_id FOR UPDATE")
                .param("groupId", groupId)
                .query(GroupMirrorStore::mirror)
                .list();
    }

    /** The mirror whose group's account in a personal ledger this is, on or off. */
    Optional<Mirror> findByAccount(UUID accountId) {
        return jdbc.sql("SELECT " + MIRROR_COLUMNS + " FROM group_mirrors WHERE account_id = :accountId")
                .param("accountId", accountId)
                .query(GroupMirrorStore::mirror)
                .optional();
    }

    /** The group of each of the person's group accounts, by account. */
    Map<UUID, UUID> groupsByAccount(UUID userId) {
        return jdbc
                .sql("SELECT account_id, group_id FROM group_mirrors WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, rowNum) ->
                        Map.entry(rs.getObject("account_id", UUID.class), rs.getObject("group_id", UUID.class)))
                .stream()
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    void save(Mirror mirror) {
        jdbc.sql("""
                        INSERT INTO group_mirrors (%s)
                        VALUES (:userId, :groupId, :memberAccountId, :accountId, :categoryId, :paymentAccountId, :enabled)
                        ON CONFLICT (user_id, group_id) DO UPDATE SET
                            category_id = EXCLUDED.category_id,
                            payment_account_id = EXCLUDED.payment_account_id,
                            enabled = EXCLUDED.enabled""".formatted(MIRROR_COLUMNS))
                .param("userId", mirror.userId())
                .param("groupId", mirror.groupId())
                .param("memberAccountId", mirror.memberAccountId())
                .param("accountId", mirror.accountId())
                .param("categoryId", mirror.categoryId())
                .param("paymentAccountId", mirror.paymentAccountId())
                .param("enabled", mirror.enabled())
                .update();
    }

    /**
     * Takes the group entry for the person: true when this transaction is the first to, false when it
     * was handled already. A second transaction claiming it at the same moment waits on the first's
     * row and then gets false, so an event delivered twice at once is still recorded once.
     */
    boolean claim(UUID groupEntryId, Mirror mirror, @Nullable EntryType type) {
        return jdbc.sql("""
                                INSERT INTO group_mirror_entries (group_entry_id, user_id, group_id, type, before_mirror)
                                VALUES (:groupEntryId, :userId, :groupId, :type, false)
                                ON CONFLICT (group_entry_id, user_id) DO NOTHING""")
                        .param("groupEntryId", groupEntryId)
                        .param("userId", mirror.userId())
                        .param("groupId", mirror.groupId())
                        .param("type", type == null ? null : type.name())
                        .update()
                == 1;
    }

    Optional<Handled> handled(UUID groupEntryId, UUID userId) {
        return jdbc.sql("SELECT " + HANDLED_COLUMNS
                        + " FROM group_mirror_entries WHERE group_entry_id = :groupEntryId AND user_id = :userId")
                .param("groupEntryId", groupEntryId)
                .param("userId", userId)
                .query(GroupMirrorStore::handled)
                .optional();
    }

    void recordedAs(UUID groupEntryId, UUID userId, UUID personalEntryId) {
        jdbc.sql("""
                        UPDATE group_mirror_entries SET personal_entry_id = :personalEntryId
                        WHERE group_entry_id = :groupEntryId AND user_id = :userId""")
                .param("personalEntryId", personalEntryId)
                .param("groupEntryId", groupEntryId)
                .param("userId", userId)
                .update();
    }

    /** A correction of an entry from before the mirror is from before it too: it only adjusted the balance. */
    void markBeforeMirror(UUID groupEntryId, UUID userId) {
        jdbc.sql("""
                        UPDATE group_mirror_entries SET before_mirror = true
                        WHERE group_entry_id = :groupEntryId AND user_id = :userId""")
                .param("groupEntryId", groupEntryId)
                .param("userId", userId)
                .update();
    }

    /**
     * Marks the entries that the opening balance of the group's account holds, whether the mirror
     * handled them in an earlier period or not, so they are never recorded one by one again.
     */
    void markBeforeMirror(Mirror mirror, Set<UUID> groupEntryIds) {
        if (groupEntryIds.isEmpty()) {
            return;
        }
        // IN (:ids) is how JdbcClient expands a collection; the rows come from journal_entries itself.
        jdbc.sql("""
                        INSERT INTO group_mirror_entries (group_entry_id, user_id, group_id, before_mirror)
                        SELECT entry.id, :userId, :groupId, true FROM journal_entries entry WHERE entry.id IN (:ids)
                        ON CONFLICT (group_entry_id, user_id) DO UPDATE SET before_mirror = true""")
                .param("userId", mirror.userId())
                .param("groupId", mirror.groupId())
                .param("ids", groupEntryIds)
                .update();
    }

    /** What the mirror recorded in these personal entries, by personal entry; the others are not here. */
    Map<UUID, Handled> byPersonalEntries(Collection<UUID> personalEntryIds) {
        if (personalEntryIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("SELECT " + HANDLED_COLUMNS + " FROM group_mirror_entries WHERE personal_entry_id IN (:ids)")
                .param("ids", personalEntryIds)
                .query(GroupMirrorStore::handled)
                .stream()
                .collect(toMap(handled -> Objects.requireNonNull(handled.personalEntryId()), Function.identity()));
    }

    private static Mirror mirror(ResultSet rs, int rowNum) throws SQLException {
        return new Mirror(
                rs.getObject("user_id", UUID.class),
                rs.getObject("group_id", UUID.class),
                rs.getObject("member_account_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getObject("category_id", UUID.class),
                rs.getObject("payment_account_id", UUID.class),
                rs.getBoolean("enabled"));
    }

    private static Handled handled(ResultSet rs, int rowNum) throws SQLException {
        String type = rs.getString("type");
        return new Handled(
                rs.getObject("group_entry_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("group_id", UUID.class),
                type == null ? null : EntryType.valueOf(type),
                rs.getObject("personal_entry_id", UUID.class),
                rs.getBoolean("before_mirror"));
    }
}
