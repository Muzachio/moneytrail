package io.github.muzachio.moneytrail.ledger.internal;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.EntryFilter;
import io.github.muzachio.moneytrail.ledger.EntryPage;
import io.github.muzachio.moneytrail.ledger.EntryPosition;
import io.github.muzachio.moneytrail.ledger.EntryTotals;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Lists and adds up the entries in effect of a ledger (LedgerService#entries and #totals), with plain
 * SQL: the keyset condition and the filters have to be visible in the query to be checked.
 */
@Repository
class EntryQueries {

    private final JdbcClient jdbc;

    EntryQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Newest first, by {@code (occurred_on, id)}: ids are UUIDv7, so within a day the entry recorded
     * last comes first. The index on {@code (ledger_id, occurred_on, id)} (V10) is read backwards,
     * from the cursor on, and {@code LIMIT} stops it after one page, however many pages came before:
     * an {@code OFFSET} would read and throw away every earlier row, and shift when an entry is added.
     */
    EntryPage page(EntryFilter filter, @Nullable EntryPosition after, int limit) {
        Where where = where(filter);
        if (after != null) {
            where.and("(entry.occurred_on, entry.id) < (:afterOn, :afterId)");
            where.params.put("afterOn", after.occurredOn());
            where.params.put("afterId", after.entryId());
        }
        // One more than a page: whether it comes back tells whether there is a next page.
        List<EntryRow> rows = jdbc.sql("""
                        SELECT entry.id, entry.ledger_id, entry.occurred_on, entry.description, entry.created_by,
                               entry.created_at, entry.reverses_entry_id, entry.replaces_entry_id
                        FROM journal_entries entry
                        WHERE %s
                        ORDER BY entry.occurred_on DESC, entry.id DESC
                        LIMIT :limit""".formatted(where.sql()))
                .params(where.params)
                .param("limit", limit + 1)
                .query((rs, rowNum) -> rowOf(rs))
                .list();
        boolean more = rows.size() > limit;
        List<EntryRow> page = more ? rows.subList(0, limit) : rows;
        Map<UUID, List<Posting>> postings =
                postingsOf(page.stream().map(EntryRow::id).toList());
        List<JournalEntry> entries = page.stream()
                .map(row -> row.toEntry(postings.getOrDefault(row.id(), List.of())))
                .toList();
        EntryPosition next = null;
        if (more) {
            JournalEntry last = entries.getLast();
            next = new EntryPosition(last.occurredOn(), last.id());
        }
        return new EntryPage(entries, next);
    }

    /**
     * The sums are {@code numeric} in Postgres, so they cannot overflow there; one that no longer fits
     * a {@code long} throws ArithmeticException instead of coming back wrong.
     */
    EntryTotals totals(EntryFilter filter) {
        Where where = where(filter);
        long count = jdbc.sql("SELECT count(*) FROM journal_entries entry WHERE " + where.sql())
                .params(where.params)
                .query(Long.class)
                .single();
        Map<AccountKind, Money> sums = jdbc
                .sql("""
                        SELECT account.kind, posting.currency, sum(posting.amount_minor) AS total
                        FROM journal_entries entry
                        JOIN postings posting ON posting.entry_id = entry.id
                        JOIN accounts account ON account.id = posting.account_id
                        WHERE %s
                        GROUP BY account.kind, posting.currency""".formatted(where.sql()))
                .params(where.params)
                .query((rs, rowNum) -> Map.entry(
                        AccountKind.valueOf(rs.getString("kind")),
                        new Money(
                                rs.getBigDecimal("total").longValueExact(),
                                Currency.getInstance(rs.getString("currency")))))
                .stream()
                // Two currencies under one kind fail here: a ledger in one currency has none (M4).
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new EntryTotals(count, sums);
    }

    /** In effect: not a reversal, and not reversed. */
    private static Where where(EntryFilter filter) {
        Where where = new Where();
        where.and("entry.ledger_id = :ledgerId");
        where.params.put("ledgerId", filter.ledgerId());
        where.and("entry.occurred_on BETWEEN :from AND :to");
        where.params.put("from", filter.from());
        where.params.put("to", filter.to());
        where.and("entry.reverses_entry_id IS NULL");
        where.and("NOT EXISTS (SELECT FROM journal_entries reversal WHERE reversal.reverses_entry_id = entry.id)");
        int i = 0;
        for (UUID accountId : filter.postingTo()) {
            String param = "account" + i++;
            where.and("EXISTS (SELECT FROM postings p WHERE p.entry_id = entry.id AND p.account_id = :" + param + ")");
            where.params.put(param, accountId);
        }
        AccountKind kind = filter.postingToKind();
        if (kind != null) {
            where.and("""
                    EXISTS (SELECT FROM postings p JOIN accounts a ON a.id = p.account_id
                            WHERE p.entry_id = entry.id AND a.kind = :kind)""");
            where.params.put("kind", kind.name());
        }
        if (!filter.notPostingToKinds().isEmpty()) {
            where.and("""
                    NOT EXISTS (SELECT FROM postings p JOIN accounts a ON a.id = p.account_id
                                WHERE p.entry_id = entry.id AND a.kind = ANY(:notKinds))""");
            where.params.put(
                    "notKinds",
                    filter.notPostingToKinds().stream().map(Enum::name).toArray(String[]::new));
        }
        String text = filter.descriptionContains();
        if (text != null) {
            // strpos, not LIKE: the user's % and _ are plain characters, with nothing to escape.
            // pg_c_utf8 folds every Unicode letter, as for category names (V8).
            where.and("strpos(lower(entry.description COLLATE pg_c_utf8), lower(:text COLLATE pg_c_utf8)) > 0");
            where.params.put("text", text);
        }
        return where;
    }

    private Map<UUID, List<Posting>> postingsOf(List<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        SELECT entry_id, account_id, currency, amount_minor FROM postings
                        WHERE entry_id = ANY(:ids) ORDER BY id""")
                .param("ids", entryIds.toArray(UUID[]::new))
                .query((rs, rowNum) -> Map.entry(
                        rs.getObject("entry_id", UUID.class),
                        new Posting(
                                rs.getObject("account_id", UUID.class),
                                new Money(rs.getLong("amount_minor"), Currency.getInstance(rs.getString("currency"))))))
                .stream()
                .collect(groupingBy(Map.Entry::getKey, mapping(Map.Entry::getValue, toList())));
    }

    private static EntryRow rowOf(ResultSet rs) throws SQLException {
        return new EntryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class),
                rs.getObject("occurred_on", LocalDate.class),
                rs.getString("description"),
                rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("reverses_entry_id", UUID.class),
                rs.getObject("replaces_entry_id", UUID.class));
    }

    private record EntryRow(
            UUID id,
            UUID ledgerId,
            LocalDate occurredOn,
            String description,
            UUID createdBy,
            Instant createdAt,
            @Nullable UUID reversesEntryId,
            @Nullable UUID replacesEntryId) {

        JournalEntry toEntry(List<Posting> postings) {
            return new JournalEntry(
                    id,
                    ledgerId,
                    occurredOn,
                    description,
                    createdBy,
                    createdAt,
                    reversesEntryId,
                    replacesEntryId,
                    postings);
        }
    }

    /** The conditions, joined with AND, and their parameters. */
    private static final class Where {

        private final StringBuilder sql = new StringBuilder();
        private final Map<String, Object> params = new HashMap<>();

        void and(String condition) {
            if (!sql.isEmpty()) {
                sql.append("\n  AND ");
            }
            sql.append(condition);
        }

        String sql() {
            return sql.toString();
        }
    }
}
