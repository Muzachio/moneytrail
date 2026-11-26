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

/** The splits of a group's expenses ({@code expense_splits}, V16), which only this module writes. */
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
}
