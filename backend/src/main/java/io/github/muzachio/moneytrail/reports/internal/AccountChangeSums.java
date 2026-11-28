package io.github.muzachio.moneytrail.reports.internal;

import static java.util.stream.Collectors.toMap;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * How much each of the user's own accounts changed over a period, read from the ledger's tables
 * like {@link CategorySums} (ADR-0011): where the income went, once the categories have said how
 * much of it was spent.
 *
 * <p>Entries that post to an {@code EQUITY} account are left out: an opening balance is money the
 * user already had, not money that moved in the period, as on the accounts page. Every other entry
 * of a personal ledger posts only to the user's accounts and categories and balances to zero, so
 * the accounts' changes always add up to the period's income less its spending.
 */
@Repository
class AccountChangeSums {

    private final JdbcClient jdbc;

    AccountChangeSums(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Each asset and liability account's change, leaving out the ones that add up to zero. The sum is
     * a {@code numeric} in Postgres, so it cannot overflow there; a sum that no longer fits a
     * {@code long} throws ArithmeticException instead of coming back wrong.
     */
    Map<UUID, Money> between(UUID ledgerId, LocalDate from, LocalDate to) {
        return jdbc
                .sql("""
                        SELECT posting.account_id, posting.currency, sum(posting.amount_minor) AS change_minor
                        FROM journal_entries entry
                        JOIN postings posting ON posting.entry_id = entry.id
                        JOIN accounts account ON account.id = posting.account_id
                        WHERE entry.ledger_id = :ledgerId
                          AND entry.occurred_on BETWEEN :from AND :to
                          AND account.kind IN ('ASSET', 'LIABILITY')
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
