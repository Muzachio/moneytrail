package io.github.muzachio.moneytrail.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.shared.DomainException;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One ledger per owner, and the accounts' names and archiving (#42). */
class LedgerAccountsTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    private LedgerOwner owner;
    private Ledger personal;

    @BeforeEach
    void openPersonalLedger() {
        owner = LedgerOwner.user(LedgerTestUsers.create(jdbc));
        personal = ledger.openLedger(owner);
    }

    @Test
    void anOwnerHasOneLedger() {
        assertThat(ledger.findLedger(owner)).contains(personal);
        assertThat(ledger.openLedgerIfAbsent(owner)).isEmpty();
        assertThatThrownBy(() -> ledger.openLedger(owner)).isInstanceOf(DataIntegrityViolationException.class);

        LedgerOwner newcomer = LedgerOwner.user(LedgerTestUsers.create(jdbc));
        assertThat(ledger.findLedger(newcomer)).isEmpty();
        Ledger opened = ledger.openLedgerIfAbsent(newcomer).orElseThrow();
        assertThat(opened.owner()).isEqualTo(newcomer);
        assertThat(ledger.findLedger(newcomer)).contains(opened);
    }

    @Test
    void listsTheLedgersAccountsInTheOrderTheyWereOpened() {
        Account checking = open(AccountKind.ASSET, "Checking");
        Account food = open(AccountKind.EXPENSE, "Food");
        Account salary = open(AccountKind.INCOME, "Salary");
        ledger.openAccount(new NewAccount(
                ledger.openLedger(LedgerOwner.user(LedgerTestUsers.create(jdbc)))
                        .id(),
                AccountKind.ASSET,
                null,
                "Someone else's",
                EUR));

        assertThat(ledger.accounts(personal.id())).containsExactly(checking, food, salary);
        assertThat(ledger.findAccount(food.id())).contains(food);
        assertThat(ledger.findAccount(UUID.randomUUID())).isEmpty();
    }

    @Test
    void renamesArchivesAndRestoresAnAccountKeepingItsBalance() {
        Account food = open(AccountKind.EXPENSE, "Food");

        Account renamed = ledger.renameAccount(food.id(), "Groceries");
        Account archived = ledger.archiveAccount(food.id());
        Account archivedAgain = ledger.archiveAccount(food.id());
        Account restored = ledger.restoreAccount(food.id());

        assertThat(renamed.name()).isEqualTo("Groceries");
        assertThat(archived.archived()).isTrue();
        assertThat(archivedAgain).isEqualTo(archived);
        assertThat(restored).isEqualTo(renamed);
        assertThat(ledger.balance(food.id())).isEqualTo(new Money(0, EUR));
        assertThatThrownBy(() -> ledger.renameAccount(UUID.randomUUID(), "Lost"))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("account_not_found"));
    }

    @Test
    void activeCategoriesOfAKindHaveDifferentNamesIgnoringCase() {
        Account health = open(AccountKind.EXPENSE, "Saúde");

        assertNameTaken(() -> open(AccountKind.EXPENSE, "SAÚDE"));
        Account food = open(AccountKind.EXPENSE, "Food");
        assertNameTaken(() -> ledger.renameAccount(food.id(), "saúde"));
        assertThat(open(AccountKind.INCOME, "Saúde").name()).as("another kind").isEqualTo("Saúde");

        ledger.archiveAccount(health.id());
        Account newHealth = open(AccountKind.EXPENSE, "saúde");
        assertThat(newHealth.archived()).isFalse();
        assertNameTaken(() -> ledger.restoreAccount(health.id()));
    }

    @Test
    void otherKindsOfAccountMayShareAName() {
        open(AccountKind.ASSET, "Checking");
        open(AccountKind.ASSET, "checking");
        open(AccountKind.EQUITY, "Opening balances");

        assertThat(open(AccountKind.EQUITY, "Opening balances").name()).isEqualTo("Opening balances");
    }

    private Account open(AccountKind kind, String name) {
        return ledger.openAccount(new NewAccount(personal.id(), kind, null, name, EUR));
    }

    private static void assertNameTaken(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class, exception -> {
            assertThat(exception.code()).isEqualTo("category_name_taken");
            assertThat(exception.status().value()).isEqualTo(409);
        });
    }
}
