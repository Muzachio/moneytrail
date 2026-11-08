package io.github.muzachio.moneytrail.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database's own guarantees, checked with plain SQL that bypasses {@link LedgerService}. Each
 * test rolls back, so it leaves no half-written entry behind.
 */
class LedgerSchemaTests extends PostgresIntegrationTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void aPostingCanOnlyBeInItsAccountsCurrency() {
        UUID userId = LedgerTestUsers.create(jdbc);
        Ledger personal = ledger.openLedger(LedgerOwner.user(userId));
        Account food = ledger.openAccount(
                new NewAccount(personal.id(), AccountKind.EXPENSE, null, "Food", Currency.getInstance("EUR")));

        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            UUID entryId = jdbc.sql("""
                            INSERT INTO journal_entries (ledger_id, occurred_on, description, created_by)
                            VALUES (:ledgerId, DATE '2026-11-08', 'Lunch', :userId) RETURNING id""")
                    .param("ledgerId", personal.id())
                    .param("userId", userId)
                    .query(UUID.class)
                    .single();
            insertPosting(entryId, food.id(), "EUR", 1250);

            assertThatThrownBy(() -> insertPosting(entryId, food.id(), "USD", -1250))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("postings_account_id_currency_fkey");
        });
    }

    private void insertPosting(UUID entryId, UUID accountId, String currency, long amountMinor) {
        jdbc.sql("""
                        INSERT INTO postings (entry_id, account_id, currency, amount_minor)
                        VALUES (:entryId, :accountId, :currency, :amountMinor)""")
                .param("entryId", entryId)
                .param("accountId", accountId)
                .param("currency", currency)
                .param("amountMinor", amountMinor)
                .update();
    }
}
