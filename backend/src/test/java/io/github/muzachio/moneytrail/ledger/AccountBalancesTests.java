package io.github.muzachio.moneytrail.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.shared.DomainException;
import io.github.muzachio.moneytrail.shared.Money;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class AccountBalancesTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID userId;
    private Ledger personal;
    private Account checking;
    private Account card;
    private Account food;
    private Account transport;
    private Account salary;

    @BeforeEach
    void openAccounts() {
        userId = LedgerTestUsers.create(jdbc);
        personal = ledger.openLedger(LedgerOwner.user(userId));
        checking = open(AccountKind.ASSET, "Checking");
        card = open(AccountKind.LIABILITY, "Credit card");
        food = open(AccountKind.EXPENSE, "Food");
        transport = open(AccountKind.EXPENSE, "Transport");
        salary = open(AccountKind.INCOME, "Salary");
    }

    @Test
    void aNewAccountStartsAtZero() {
        assertThat(ledger.balance(checking.id())).isEqualTo(Money.zero(EUR));
        assertThat(jdbc.sql("SELECT count(*) FROM account_balances WHERE account_id = :id")
                        .param("id", checking.id())
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @Test
    void everyBalanceEqualsTheSumOfItsPostings() {
        post(posting(salary, -250000), posting(checking, 250000));
        post(posting(food, 1250), posting(checking, -1250));
        post(posting(transport, 4000), posting(card, -4000));
        // One account twice in an entry: lunch and a coffee from checking, paid separately.
        post(posting(food, 1500), posting(food, 150), posting(checking, -1500), posting(checking, -150));
        post(posting(card, 4000), posting(checking, -4000));

        assertThat(ledger.balance(checking.id())).isEqualTo(eur(250000 - 1250 - 1650 - 4000));
        assertThat(ledger.balance(card.id())).isEqualTo(eur(0));
        assertThat(ledger.balance(food.id())).isEqualTo(eur(2900));
        assertThat(ledger.balance(transport.id())).isEqualTo(eur(4000));
        assertThat(ledger.balance(salary.id())).isEqualTo(eur(-250000));
        for (Account account : List.of(checking, card, food, transport, salary)) {
            assertThat(ledger.balance(account.id()).minor()).isEqualTo(sumOfPostings(account));
        }
    }

    @Test
    void aRefusedEntryLeavesEveryBalanceAsItWas() {
        post(posting(food, 1250), posting(checking, -1250));
        Ledger someoneElses = ledger.openLedger(LedgerOwner.user(LedgerTestUsers.create(jdbc)));
        Account theirChecking =
                ledger.openAccount(new NewAccount(someoneElses.id(), AccountKind.ASSET, null, "Checking", EUR));

        assertThatThrownBy(() -> post(posting(food, 500), posting(theirChecking, -500)))
                .isInstanceOf(DomainException.class);

        assertThat(ledger.balance(food.id())).isEqualTo(eur(1250));
        assertThat(ledger.balance(theirChecking.id())).isEqualTo(eur(0));
    }

    @Test
    void balancesHasEveryAccountOfTheLedgerAndNoOther() {
        post(posting(salary, -250000), posting(checking, 250000));
        post(posting(transport, 4000), posting(card, -4000));
        Ledger someoneElses = ledger.openLedger(LedgerOwner.user(LedgerTestUsers.create(jdbc)));
        ledger.openAccount(new NewAccount(someoneElses.id(), AccountKind.ASSET, null, "Checking", EUR));

        assertThat(ledger.balances(personal.id()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        checking.id(), eur(250000),
                        card.id(), eur(-4000),
                        food.id(), eur(0),
                        transport.id(), eur(4000),
                        salary.id(), eur(-250000)));
    }

    @Test
    void changesSumTheEntriesOfTheDaysAskedForLeavingOutEquity() {
        Account openingBalances = open(AccountKind.EQUITY, "Opening balances");
        LocalDate first = LocalDate.of(2026, 11, 1);
        LocalDate last = LocalDate.of(2026, 11, 30);
        postOn(first.minusDays(1), posting(food, 900), posting(checking, -900));
        postOn(first, posting(salary, -250000), posting(checking, 250000));
        postOn(first.plusDays(4), posting(checking, 100000), posting(openingBalances, -100000));
        postOn(first.plusDays(9), posting(food, 1250), posting(card, -1250));
        postOn(last, posting(card, 1250), posting(checking, -1250));
        postOn(last.plusDays(1), posting(transport, 4000), posting(checking, -4000));

        assertThat(ledger.changes(personal.id(), first, last))
                .as("both days included; the card's payment cancels its charge, so it did not change")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        checking.id(), eur(250000 - 1250),
                        salary.id(), eur(-250000),
                        food.id(), eur(1250)));
        assertThat(ledger.changes(personal.id(), last.plusDays(2), last.plusDays(30)))
                .isEmpty();
    }

    @Test
    void anUnknownAccountHasNoBalance() {
        assertThatThrownBy(() -> ledger.balance(UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("account_not_found"));
    }

    // While post() waits for the lower account, it must not hold the higher one yet: it locks in
    // ascending id order, whatever the order of the postings. Waiting for the higher account, it
    // already holds the lower one.
    @Test
    void postLocksBalancesInAscendingAccountIdOrder() throws Exception {
        UUID lower = jdbc.sql("SELECT least(:food::uuid, :checking::uuid)")
                .param("food", food.id())
                .param("checking", checking.id())
                .query(UUID.class)
                .single();
        UUID higher = lower.equals(food.id()) ? checking.id() : food.id();
        // Postgres orders uuids differently from UUID.compareTo, so the order comes from the query.
        Posting[] highestFirst = higher.equals(food.id())
                ? new Posting[] {posting(food, 1250), posting(checking, -1250)}
                : new Posting[] {posting(checking, -1250), posting(food, 1250)};

        assertThat(heldWhilePostWaitsFor(lower, highestFirst)).doesNotContain(higher);
        assertThat(heldWhilePostWaitsFor(higher, highestFirst)).contains(lower);
        assertThat(ledger.balance(checking.id())).isEqualTo(eur(-2500));
    }

    /**
     * Locks one balance, starts a post() that needs it, and returns the other balances post() holds
     * while it waits. Then lets post() finish.
     */
    private List<UUID> heldWhilePostWaitsFor(UUID blocked, Posting... postings) throws Exception {
        List<UUID> held = new ArrayList<>();
        CompletableFuture<JournalEntry> waiting = transaction.execute(status -> {
            lockBalance(blocked);
            CompletableFuture<JournalEntry> post = CompletableFuture.supplyAsync(() -> post(postings));
            assertThatThrownBy(() -> post.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            for (UUID other : List.of(food.id(), checking.id())) {
                if (!other.equals(blocked) && isLockedElsewhere(other)) {
                    held.add(other);
                }
            }
            return post;
        });
        assertThat(waiting).isNotNull();
        waiting.get(10, TimeUnit.SECONDS);
        return held;
    }

    private void lockBalance(UUID accountId) {
        jdbc.sql("SELECT balance_minor FROM account_balances WHERE account_id = :id FOR UPDATE")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }

    // From a third connection: NOWAIT fails at once if another transaction holds the row.
    private boolean isLockedElsewhere(UUID accountId) {
        TransactionTemplate separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            separate.executeWithoutResult(status -> {
                status.setRollbackOnly();
                jdbc.sql("SELECT balance_minor FROM account_balances WHERE account_id = :id FOR UPDATE NOWAIT")
                        .param("id", accountId)
                        .query(Long.class)
                        .single();
            });
            return false;
        } catch (DataAccessException exception) {
            // 55P03 lock_not_available; Spring leaves it uncategorized.
            if (exception.getMostSpecificCause() instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                return true;
            }
            throw exception;
        }
    }

    private Account open(AccountKind kind, String name) {
        return ledger.openAccount(new NewAccount(personal.id(), kind, null, name, EUR));
    }

    private JournalEntry post(Posting... postings) {
        return postOn(LocalDate.of(2026, 11, 8), postings);
    }

    private JournalEntry postOn(LocalDate occurredOn, Posting... postings) {
        return ledger.post(new NewJournalEntry(personal.id(), occurredOn, "Test", userId, List.of(postings)));
    }

    private long sumOfPostings(Account account) {
        return jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM postings WHERE account_id = :id")
                .param("id", account.id())
                .query(Long.class)
                .single();
    }

    private static Posting posting(Account account, long minor) {
        return new Posting(account.id(), eur(minor));
    }

    private static Money eur(long minor) {
        return new Money(minor, EUR);
    }
}
