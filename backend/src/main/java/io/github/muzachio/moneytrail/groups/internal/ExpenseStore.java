package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.groups.internal.Split.MemberShare;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The splits of a group's expenses ({@code expense_splits}, V16), and the reads of {@code
 * group_members} that recording an expense needs. Only this module writes these tables.
 */
@Repository
class ExpenseStore {

    private final JdbcClient jdbc;

    ExpenseStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores the split of the expense posted as {@code entryId}, a share of zero included. */
    void insertSplit(UUID entryId, Split split) {
        for (MemberShare share : split.shares()) {
            jdbc.sql("""
                            INSERT INTO expense_splits (entry_id, user_id, method, input_value, share_minor)
                            VALUES (:entryId, :userId, :method, :input, :share)""")
                    .param("entryId", entryId)
                    .param("userId", share.memberId())
                    .param("method", split.method().name())
                    .param("input", share.input())
                    .param("share", share.share().minor())
                    .update();
        }
    }

    /**
     * The splits of the entries that are expenses; an entry with no split, such as a reversal, is not
     * in the map. The shares come in the order of the members' ids as text, as {@link Splits} makes
     * them.
     */
    Map<UUID, Split> splitsOf(Collection<UUID> entryIds, Currency currency) {
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        record Row(UUID entryId, SplitMethod method, MemberShare share) {}
        List<Row> rows = jdbc.sql("""
                        SELECT entry_id, user_id, method, input_value, share_minor
                        FROM expense_splits
                        WHERE entry_id IN (:entryIds)""")
                .param("entryIds", entryIds)
                .query((rs, rowNum) -> new Row(
                        rs.getObject("entry_id", UUID.class),
                        SplitMethod.valueOf(rs.getString("method")),
                        new MemberShare(
                                rs.getObject("user_id", UUID.class),
                                rs.getLong("input_value"),
                                new Money(rs.getLong("share_minor"), currency))))
                .list();
        Map<UUID, List<Row>> byEntry = new LinkedHashMap<>();
        rows.forEach(row -> byEntry.computeIfAbsent(row.entryId(), entryId -> new ArrayList<>())
                .add(row));
        Map<UUID, Split> splits = new HashMap<>();
        byEntry.forEach((entryId, split) -> {
            List<MemberShare> shares = split.stream()
                    .map(Row::share)
                    .sorted(Comparator.comparing(share -> share.memberId().toString()))
                    .toList();
            Money total = shares.stream().map(MemberShare::share).reduce(Money.zero(currency), Money::plus);
            splits.put(entryId, new Split(split.getFirst().method(), total, shares));
        });
        return splits;
    }

    /**
     * The owner of every member account of the group, by account: members who left included, since
     * their account and their part in past expenses stay (ADR-0012).
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
     * so none of them leaves while the expense changes their balance: leaving locks its row {@code FOR
     * UPDATE} and checks the balance (see {@link GroupStore#lockMembership}). In the order of the ids,
     * like every lock taken here.
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
}
