package io.github.muzachio.moneytrail.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database's own guarantees (ADR-0004), checked with plain SQL that bypasses {@link
 * LedgerService}: they hold even if the service has a bug or someone edits the data by hand.
 */
class LedgerSchemaTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transaction;

    private UUID userId;
    private Ledger personal;
    private Account food;
    private Account checking;
    private Account dollars;

    @BeforeEach
    void openAccounts() {
        userId = LedgerTestUsers.create(jdbc);
        personal = ledger.openLedger(LedgerOwner.user(userId));
        food = ledger.openAccount(new NewAccount(personal.id(), AccountKind.EXPENSE, null, "Food", EUR));
        checking = ledger.openAccount(new NewAccount(personal.id(), AccountKind.ASSET, null, "Checking", EUR));
        dollars = ledger.openAccount(new NewAccount(personal.id(), AccountKind.ASSET, null, "Dollars", USD));
    }

    @Test
    void aBalancedEntryCommitsAlthoughItIsUnbalancedUntilItsLastPosting() {
        assertThatCode(() -> commitEntry(posting(food, 1250), posting(checking, -1250)))
                .doesNotThrowAnyException();

        assertThat(entriesInLedger()).isOne();
    }

    @Test
    void anUnbalancedEntryFailsAtCommit() {
        assertRejectedAtCommit("does not sum to zero", posting(food, 1250), posting(checking, -1000));
    }

    @Test
    void anEntryBalancedOnlyAcrossCurrenciesFailsAtCommit() {
        assertRejectedAtCommit("does not sum to zero", posting(food, 1000), posting(dollars, -1000));
    }

    @Test
    void anEntryWithASinglePostingFailsAtCommit() {
        assertRejectedAtCommit("fewer than 2 postings", posting(food, 1250));
    }

    @Test
    void anEntryWithoutPostingsFailsAtCommit() {
        assertRejectedAtCommit("fewer than 2 postings");
    }

    @Test
    void aPostingAddedLaterMustKeepItsEntryBalanced() {
        UUID lunch = postLunch().id();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> insertPosting(lunch, posting(food, 100))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("does not sum to zero");
        assertThat(postingsOf(lunch)).isEqualTo(2);
    }

    @Test
    void aPostingCanOnlyBeInItsAccountsCurrency() {
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            UUID entryId = insertEntry();
            insertPosting(entryId, posting(food, 1250));

            assertThatThrownBy(() -> insertPosting(entryId, new Posting(food.id(), new Money(-1250, USD))))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("postings_account_id_currency_fkey");
        });
    }

    // The postings UPDATE keeps the entry balanced, so only the append-only rule can refuse it.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE journal_entries SET description = 'Dinner' WHERE id = :id",
                "DELETE FROM journal_entries WHERE id = :id",
                "UPDATE postings SET amount_minor = -amount_minor WHERE entry_id = :id",
                "DELETE FROM postings WHERE entry_id = :id",
                "TRUNCATE postings",
                "TRUNCATE journal_entries CASCADE"
            })
    void historyCannotBeChanged(String sql) {
        JournalEntry lunch = postLunch();

        // Rolled back whatever happens, so a broken trigger cannot wipe other tests' data.
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            assertThatThrownBy(() -> {
                        var statement = jdbc.sql(sql);
                        if (sql.contains(":id")) {
                            statement = statement.param("id", lunch.id());
                        }
                        statement.update();
                    })
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("the ledger is append-only");
        });
        assertThat(ledger.findEntry(lunch.id())).contains(lunch);
    }

    private JournalEntry postLunch() {
        return ledger.post(new NewJournalEntry(
                personal.id(),
                LocalDate.of(2026, 11, 8),
                "Lunch",
                userId,
                List.of(posting(food, 1250), posting(checking, -1250))));
    }

    /** One statement per row, as {@link LedgerService} writes them, then a commit. */
    private void commitEntry(Posting... postings) {
        transaction.executeWithoutResult(status -> {
            UUID entryId = insertEntry();
            for (Posting posting : postings) {
                insertPosting(entryId, posting);
            }
        });
    }

    private void assertRejectedAtCommit(String reason, Posting... postings) {
        assertThatThrownBy(() -> commitEntry(postings))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(reason);
        assertThat(entriesInLedger()).isZero();
    }

    private UUID insertEntry() {
        return jdbc.sql("""
                        INSERT INTO journal_entries (ledger_id, occurred_on, description, created_by)
                        VALUES (:ledgerId, DATE '2026-11-08', 'Lunch', :userId) RETURNING id""")
                .param("ledgerId", personal.id())
                .param("userId", userId)
                .query(UUID.class)
                .single();
    }

    private void insertPosting(UUID entryId, Posting posting) {
        jdbc.sql("""
                        INSERT INTO postings (entry_id, account_id, currency, amount_minor)
                        VALUES (:entryId, :accountId, :currency, :amountMinor)""")
                .param("entryId", entryId)
                .param("accountId", posting.accountId())
                .param("currency", posting.amount().currency().getCurrencyCode())
                .param("amountMinor", posting.amount().minor())
                .update();
    }

    private long entriesInLedger() {
        return jdbc.sql("SELECT count(*) FROM journal_entries WHERE ledger_id = :ledgerId")
                .param("ledgerId", personal.id())
                .query(Long.class)
                .single();
    }

    private long postingsOf(UUID entryId) {
        return jdbc.sql("SELECT count(*) FROM postings WHERE entry_id = :entryId")
                .param("entryId", entryId)
                .query(Long.class)
                .single();
    }

    private Posting posting(Account account, long minor) {
        return new Posting(account.id(), new Money(minor, account.currency()));
    }
}
