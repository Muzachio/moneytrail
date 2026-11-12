package io.github.muzachio.moneytrail.ledger.internal;

import static java.util.stream.Collectors.toMap;

import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code account_balances} projection, with plain SQL: the ledger's hot path, where the order
 * of the locks matters and must be visible in the query.
 */
@Repository
class AccountBalances {

    private final JdbcClient jdbc;

    AccountBalances(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void open(UUID accountId) {
        jdbc.sql("INSERT INTO account_balances (account_id) VALUES (:accountId)")
                .param("accountId", accountId)
                .update();
    }

    /**
     * Adds the postings to their accounts' balances. Every row is locked first, in one statement and
     * in ascending account id order: two entries that touch the same accounts then queue up for them
     * in the same order and cannot deadlock, whatever the order of their postings.
     */
    void apply(List<Posting> postings) {
        Map<UUID, Long> changes = new HashMap<>();
        postings.forEach(
                posting -> changes.merge(posting.accountId(), posting.amount().minor(), Math::addExact));
        jdbc.sql("SELECT account_id FROM account_balances WHERE account_id = ANY(:ids) ORDER BY account_id FOR UPDATE")
                .param("ids", changes.keySet().toArray(UUID[]::new))
                .query(UUID.class)
                .list();
        changes.forEach((accountId, change) -> jdbc.sql("""
                        UPDATE account_balances
                        SET balance_minor = balance_minor + :change, version = version + 1, updated_at = now()
                        WHERE account_id = :accountId""")
                .param("change", change)
                .param("accountId", accountId)
                .update());
    }

    Optional<Money> find(UUID accountId) {
        return jdbc.sql("""
                        SELECT balance.balance_minor, account.currency
                        FROM account_balances balance JOIN accounts account ON account.id = balance.account_id
                        WHERE balance.account_id = :accountId""")
                .param("accountId", accountId)
                .query((rs, rowNum) -> balanceOf(rs))
                .optional();
    }

    Map<UUID, Money> findByLedger(UUID ledgerId) {
        return jdbc
                .sql("""
                        SELECT balance.account_id, balance.balance_minor, account.currency
                        FROM account_balances balance JOIN accounts account ON account.id = balance.account_id
                        WHERE account.ledger_id = :ledgerId""")
                .param("ledgerId", ledgerId)
                .query((rs, rowNum) -> Map.entry(rs.getObject("account_id", UUID.class), balanceOf(rs)))
                .stream()
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Money balanceOf(ResultSet rs) throws SQLException {
        return new Money(rs.getLong("balance_minor"), Currency.getInstance(rs.getString("currency")));
    }
}
