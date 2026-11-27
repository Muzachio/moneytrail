package io.github.muzachio.moneytrail.groups.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V18 on the expenses groups already had: a database of its own in the tests' Postgres, migrated to
 * V17 and filled as the app left it, then migrated to V18.
 */
class ExpenseCategoriesMigrationTests extends PostgresIntegrationTest {

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private JdbcClient jdbc;

    private final String database = "v18_" + UUID.randomUUID().toString().replace("-", "");

    private DataSource dataSource;

    private JdbcClient before;

    @BeforeEach
    void migrateADatabaseOfItsOwnToV17() {
        jdbc.sql("CREATE DATABASE " + database).update();
        dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://%s:%d/%s".formatted(postgres.getHost(), postgres.getMappedPort(5432), database),
                postgres.getUsername(),
                postgres.getPassword());
        migrateTo("17");
        before = JdbcClient.create(dataSource);
    }

    @AfterEach
    void dropIt() {
        jdbc.sql("DROP DATABASE " + database + " WITH (FORCE)").update();
    }

    @Test
    void givesOtherToEveryVersionOfEveryExpenseAndNothingToReversalsOrSettlements() {
        UUID ana = user();
        UUID joao = user();
        UUID ledger = groupLedger();
        UUID anas = memberAccount(ledger);
        UUID joaos = memberAccount(ledger);
        UUID dinner = entry(ledger, ana, null, anas, joaos, 1000);
        split(dinner, joao, 1000);
        UUID reversal = entry(ledger, ana, dinner, joaos, anas, 1000);
        UUID lunch = entry(ledger, ana, null, anas, joaos, 600);
        split(lunch, ana, 0);
        split(lunch, joao, 600);
        UUID payment = entry(ledger, joao, null, joaos, anas, 600);
        before.sql("INSERT INTO settlements (entry_id) VALUES (:id)")
                .param("id", payment)
                .update();

        migrateTo("18");

        JdbcClient after = JdbcClient.create(dataSource);
        record Row(UUID entryId, String category) {}
        List<Row> rows = after.sql("SELECT entry_id, category FROM expense_categories")
                .query((rs, rowNum) -> new Row(rs.getObject("entry_id", UUID.class), rs.getString("category")))
                .list();
        assertThat(rows).containsExactlyInAnyOrder(new Row(dinner, "other"), new Row(lunch, "other"));
        assertThat(rows).extracting(Row::entryId).doesNotContain(reversal, payment);
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(dataSource).target(version).load().migrate();
    }

    private UUID user() {
        return before.sql("INSERT INTO users (email, name) VALUES (:email, 'Someone') RETURNING id")
                .param("email", "v18-" + UUID.randomUUID() + "@example.com")
                .query(UUID.class)
                .single();
    }

    private UUID groupLedger() {
        return before.sql("INSERT INTO ledgers (owner_type, owner_id) VALUES ('GROUP', :owner) RETURNING id")
                .param("owner", UUID.randomUUID())
                .query(UUID.class)
                .single();
    }

    private UUID memberAccount(UUID ledgerId) {
        return before.sql("""
                        INSERT INTO accounts (ledger_id, kind, name, currency)
                        VALUES (:ledgerId, 'GROUP_MEMBER', 'Member', 'EUR')
                        RETURNING id""").param("ledgerId", ledgerId).query(UUID.class).single();
    }

    /**
     * One balanced entry, {@code +amount} to {@code debit} and {@code −amount} to {@code credit}, in a
     * single statement: the balance trigger is checked at commit, so the postings must go with it.
     */
    private UUID entry(UUID ledgerId, UUID createdBy, @Nullable UUID reverses, UUID debit, UUID credit, long amount) {
        return before.sql("""
                        WITH entry AS (
                            INSERT INTO journal_entries (ledger_id, occurred_on, description, reverses_entry_id, created_by)
                            VALUES (:ledgerId, DATE '2026-11-14', 'Dinner', :reverses, :createdBy)
                            RETURNING id
                        ), debit AS (
                            INSERT INTO postings (entry_id, account_id, currency, amount_minor)
                            SELECT id, :debit, 'EUR', :amount FROM entry
                        ), credit AS (
                            INSERT INTO postings (entry_id, account_id, currency, amount_minor)
                            SELECT id, :credit, 'EUR', -:amount FROM entry
                        )
                        SELECT id FROM entry""")
                .param("ledgerId", ledgerId)
                .param("reverses", reverses, Types.OTHER)
                .param("createdBy", createdBy)
                .param("debit", debit)
                .param("credit", credit)
                .param("amount", amount)
                .query(UUID.class)
                .single();
    }

    private void split(UUID entryId, UUID userId, long share) {
        before.sql("""
                        INSERT INTO expense_splits (entry_id, user_id, method, input_value, share_minor)
                        VALUES (:entryId, :userId, 'EQUALLY', 1, :share)""")
                .param("entryId", entryId)
                .param("userId", userId)
                .param("share", share)
                .update();
    }
}
