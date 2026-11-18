package io.github.muzachio.moneytrail.personal.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
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
 * V12 on the categories people already had: a database of its own in the tests' Postgres, migrated
 * to V11 and filled as the app left it, then migrated to V12.
 */
class CategoryKeysMigrationTests extends PostgresIntegrationTest {

    @Autowired
    private PostgreSQLContainer postgres;

    @Autowired
    private JdbcClient jdbc;

    private final String database = "v12_" + UUID.randomUUID().toString().replace("-", "");

    private DataSource dataSource;

    private JdbcClient before;

    @BeforeEach
    void migrateADatabaseOfItsOwnToV11() {
        jdbc.sql("CREATE DATABASE " + database).update();
        dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://%s:%d/%s".formatted(postgres.getHost(), postgres.getMappedPort(5432), database),
                postgres.getUsername(),
                postgres.getPassword());
        migrateTo("11");
        before = JdbcClient.create(dataSource);
    }

    @AfterEach
    void dropIt() {
        jdbc.sql("DROP DATABASE " + database + " WITH (FORCE)").update();
    }

    @Test
    void callsTheCategoriesWithANameOnTheListByItsKeyUnlessThatRepeatsAName() {
        UUID ana = ledger();
        UUID food = category(ana, "EXPENSE", "food", "Food", false);
        UUID healthInPortuguese = category(ana, "EXPENSE", "health", "Saúde", false);
        UUID leisureRenamed = category(ana, "EXPENSE", "leisure", "Diversão", false);
        UUID shoppingAsGroceries = category(ana, "EXPENSE", "shopping", "Supermercado", false);
        UUID foodAgain = category(ana, "EXPENSE", null, "Alimentação", false);
        UUID gym = category(ana, "EXPENSE", null, "GINÁSIO", false);
        UUID pets = category(ana, "EXPENSE", null, "pets", false);
        UUID archivedTravel = category(ana, "EXPENSE", null, "Viagens", true);
        UUID travel = category(ana, "EXPENSE", null, "Travel", false);
        UUID presents = category(ana, "EXPENSE", null, "Presentes", false);
        UUID pilates = category(ana, "EXPENSE", null, "Pilates", false);
        UUID gifts = category(ana, "INCOME", "gifts", "Gifts", false);
        UUID giftsAgain = category(ana, "INCOME", null, "Presentes", false);
        UUID interest = category(ana, "INCOME", null, "Juros", false);
        UUID asset = category(ana, "ASSET", null, "Food", false);
        UUID rui = ledger();
        UUID ruisFood = category(rui, "EXPENSE", null, "Alimentação", false);
        UUID ruisEducation = category(rui, "EXPENSE", null, "Educação", false);

        migrateTo("12");

        assertCalled(food, "food", "Food");
        assertCalled(healthInPortuguese, "health", "Health");
        assertCalled(leisureRenamed, null, "Diversão");
        // A default keeps its subtype, so it stays the default shopping, now called Groceries.
        assertCalled(shoppingAsGroceries, "groceries", "Groceries");
        // "Food" is taken by the default: renaming this one would repeat it.
        assertCalled(foodAgain, null, "Alimentação");
        assertCalled(gym, "gym", "Gym");
        assertCalled(pets, "pets", "Pets");
        // Archived categories may share a name.
        assertCalled(archivedTravel, "travel", "Travel");
        assertCalled(travel, "travel", "Travel");
        assertCalled(presents, "gifts-given", "Gifts");
        assertCalled(pilates, null, "Pilates");
        assertCalled(gifts, "gifts", "Gifts");
        assertCalled(giftsAgain, null, "Presentes");
        assertCalled(interest, "interest", "Interest");
        assertCalled(asset, null, "Food");
        // Another ledger: nothing there is called Food.
        assertCalled(ruisFood, "food", "Food");
        assertCalled(ruisEducation, "education", "Education");
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(dataSource).target(version).load().migrate();
    }

    private UUID ledger() {
        return before.sql("INSERT INTO ledgers (owner_type, owner_id) VALUES ('USER', :owner) RETURNING id")
                .param("owner", UUID.randomUUID())
                .query(UUID.class)
                .single();
    }

    private UUID category(UUID ledgerId, String kind, @Nullable String subtype, String name, boolean archived) {
        return before.sql("""
                        INSERT INTO accounts (ledger_id, kind, subtype, name, currency, archived_at)
                        VALUES (:ledgerId, :kind, :subtype, :name, 'EUR', :archivedAt)
                        RETURNING id""")
                .param("ledgerId", ledgerId)
                .param("kind", kind)
                .param("subtype", subtype, Types.VARCHAR)
                .param("name", name)
                .param(
                        "archivedAt",
                        archived ? OffsetDateTime.now(ZoneOffset.UTC) : null,
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .query(UUID.class)
                .single();
    }

    private void assertCalled(UUID accountId, @Nullable String key, String name) {
        JdbcClient after = JdbcClient.create(dataSource);
        assertThat(after.sql("SELECT key FROM category_keys WHERE category_id = :id")
                        .param("id", accountId)
                        .query(String.class)
                        .optional())
                .as("the key of %s", name)
                .isEqualTo(Optional.ofNullable(key));
        assertThat(after.sql("SELECT name FROM accounts WHERE id = :id")
                        .param("id", accountId)
                        .query(String.class)
                        .single())
                .isEqualTo(name);
    }
}
