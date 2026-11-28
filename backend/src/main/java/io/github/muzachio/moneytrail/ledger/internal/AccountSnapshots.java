package io.github.muzachio.moneytrail.ledger.internal;

import io.github.muzachio.moneytrail.ledger.AccountSnapshot;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** An account's balance and the entries behind it, for LedgerService#snapshot. */
@Repository
class AccountSnapshots {

    private final JdbcClient jdbc;

    AccountSnapshots(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * First takes the account's {@code account_balances} row {@code FOR SHARE}, until the caller's
     * transaction ends. Posting to the account locks that row {@code FOR UPDATE}, so an entry is either
     * committed before the lock, and then in the read that follows, or waits until the caller has
     * committed: the caller acts on the snapshot before any newer entry exists for anyone else. The
     * read itself is one statement, so its sum and its list agree; the balance is summed from the
     * postings with {@link Money#plus}, which throws instead of overflowing.
     *
     * <p>The lock is a share lock on one row, taken before the caller posts anything: no transaction
     * waits for it while holding a lock this one then needs, so the ascending-order rule for several
     * rows does not come into it.
     *
     * @return empty when there is no such account
     */
    Optional<AccountSnapshot> of(UUID accountId) {
        jdbc.sql("SELECT account_id FROM account_balances WHERE account_id = :accountId FOR SHARE")
                .param("accountId", accountId)
                .query(UUID.class)
                .optional();
        List<Row> rows = jdbc.sql("""
                        SELECT account.currency, posting.entry_id, posting.amount_minor
                        FROM accounts account LEFT JOIN postings posting ON posting.account_id = account.id
                        WHERE account.id = :accountId""")
                .param("accountId", accountId)
                .query((rs, rowNum) -> new Row(
                        Currency.getInstance(rs.getString("currency")),
                        rs.getObject("entry_id", UUID.class),
                        rs.getLong("amount_minor")))
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Money balance = Money.zero(rows.getFirst().currency());
        Set<UUID> entryIds = new HashSet<>();
        for (Row row : rows) {
            UUID entryId = row.entryId();
            if (entryId != null) {
                balance = balance.plus(new Money(row.amountMinor(), row.currency()));
                entryIds.add(entryId);
            }
        }
        return Optional.of(new AccountSnapshot(balance, entryIds));
    }

    /** {@code entryId} is null for an account with no postings, which the left join still returns. */
    private record Row(Currency currency, @Nullable UUID entryId, long amountMinor) {}
}
