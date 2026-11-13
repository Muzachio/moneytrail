package io.github.muzachio.moneytrail.reports.internal;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The one query of the reports: it reads the ledger's tables and never writes them (ADR-0011).
 *
 * <p>It adds up every posting to a category, with no special case for any kind of entry, and the
 * ledger's rules make that come out right. A transfer posts to two of the user's accounts and an
 * opening balance to an account and {@code EQUITY}, so neither reaches a category. A reversal posts
 * the exact opposite of the entry it cancels on the same day, so the two add up to zero in that
 * month; an edit is a reversal plus the corrected entry, which is then the only one left counting.
 */
@Repository
class CategorySums {

    private final JdbcClient jdbc;

    CategorySums(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Each category's sum in each calendar month of the period, leaving out the ones that add up to
     * zero. The ledger and days are what the index on {@code (ledger_id, occurred_on, id)} (V10)
     * reads. The sum is a {@code numeric} in Postgres, so it cannot overflow there; a sum that no
     * longer fits a {@code long} throws ArithmeticException instead of coming back wrong.
     */
    List<CategorySum> byMonth(UUID ledgerId, ReportPeriod period) {
        return jdbc.sql("""
                        SELECT date_trunc('month', entry.occurred_on::timestamp)::date AS month,
                               category.id AS category_id, category.kind, category.currency,
                               sum(posting.amount_minor) AS amount_minor
                        FROM journal_entries entry
                        JOIN postings posting ON posting.entry_id = entry.id
                        JOIN accounts category ON category.id = posting.account_id
                        WHERE entry.ledger_id = :ledgerId
                          AND entry.occurred_on BETWEEN :from AND :to
                          AND category.kind IN ('EXPENSE', 'INCOME')
                        GROUP BY 1, category.id
                        HAVING sum(posting.amount_minor) <> 0""")
                .param("ledgerId", ledgerId)
                .param("from", period.from())
                .param("to", period.to())
                .query((rs, rowNum) -> new CategorySum(
                        YearMonth.from(rs.getObject("month", LocalDate.class)),
                        rs.getObject("category_id", UUID.class),
                        CategorySum.Kind.valueOf(rs.getString("kind")),
                        new Money(
                                rs.getBigDecimal("amount_minor").longValueExact(),
                                Currency.getInstance(rs.getString("currency")))))
                .list();
    }
}
