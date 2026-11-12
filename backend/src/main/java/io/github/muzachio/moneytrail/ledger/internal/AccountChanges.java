package io.github.muzachio.moneytrail.ledger.internal;

import static java.util.stream.Collectors.toMap;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Sums of postings over a range of days: how much accounts changed, for LedgerService#changes. */
@Repository
class AccountChanges {

    private final JdbcClient jdbc;

    AccountChanges(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The sum is a {@code numeric} in Postgres, so it cannot overflow there; a sum that no longer fits
     * a {@code long} throws ArithmeticException instead of coming back wrong.
     */
    Map<UUID, Money> between(UUID ledgerId, LocalDate from, LocalDate to) {
        return jdbc
                .sql("""
                        SELECT posting.account_id, posting.currency, sum(posting.amount_minor) AS change_minor
                        FROM journal_entries entry JOIN postings posting ON posting.entry_id = entry.id
                        WHERE entry.ledger_id = :ledgerId
                          AND entry.occurred_on BETWEEN :from AND :to
                          AND NOT EXISTS (
                              SELECT FROM postings other JOIN accounts equity ON equity.id = other.account_id
                              WHERE other.entry_id = entry.id AND equity.kind = 'EQUITY')
                        GROUP BY posting.account_id, posting.currency
                        HAVING sum(posting.amount_minor) <> 0""")
                .param("ledgerId", ledgerId)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> Map.entry(
                        rs.getObject("account_id", UUID.class),
                        new Money(
                                rs.getBigDecimal("change_minor").longValueExact(),
                                Currency.getInstance(rs.getString("currency")))))
                .stream()
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
