package io.github.muzachio.moneytrail.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.shared.DomainException;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link LedgerService#snapshot}: an account's balance with the entries it adds up, and no posting to
 * the account until the caller's transaction ends (ADR-0015). Names and amounts are invented.
 */
class AccountSnapshotTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 28);

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private UUID recordedBy;
    private Ledger group;
    private Account ana;
    private Account joao;

    @BeforeEach
    void aGroupOfTwo() {
        recordedBy = LedgerTestUsers.create(jdbc);
        group = ledger.openLedger(LedgerOwner.group(UUID.randomUUID()));
        ana = ledger.openAccount(new NewAccount(group.id(), AccountKind.GROUP_MEMBER, null, "Ana", EUR));
        joao = ledger.openAccount(new NewAccount(group.id(), AccountKind.GROUP_MEMBER, null, "João", EUR));
    }

    @Test
    void readsTheBalanceWithEveryEntryBehindItReversalsIncluded() {
        JournalEntry dinner = post(3000);
        JournalEntry taxi = post(1200);
        JournalEntry reversal = ledger.reverse(taxi.id(), recordedBy);

        AccountSnapshot snapshot = transactions.execute(status -> ledger.snapshot(ana.id()));

        assertThat(snapshot.balance()).isEqualTo(ledger.balance(ana.id())).isEqualTo(eur(3000));
        assertThat(snapshot.entryIds()).containsExactlyInAnyOrder(dinner.id(), taxi.id(), reversal.id());
    }

    @Test
    void anAccountWithNoPostingIsAtZero() {
        AccountSnapshot snapshot = transactions.execute(status -> ledger.snapshot(ana.id()));

        assertThat(snapshot.balance()).isEqualTo(eur(0));
        assertThat(snapshot.entryIds()).isEmpty();
    }

    @Test
    void anUnknownAccountIsNotFound() {
        assertThatThrownBy(() -> transactions.execute(status -> ledger.snapshot(UUID.randomUUID())))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code())
                .isEqualTo("account_not_found");
    }

    @Test
    void onlyRunsInTheCallersTransaction() {
        assertThatThrownBy(() -> ledger.snapshot(ana.id())).isInstanceOf(IllegalTransactionStateException.class);
    }

    /** A posting to the account waits until the transaction that took the snapshot has committed. */
    @Test
    void holdsOffPostingsToTheAccountUntilTheTransactionEnds() throws Exception {
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<AccountSnapshot> holder = CompletableFuture.supplyAsync(() -> transactions.execute(status -> {
            AccountSnapshot snapshot = ledger.snapshot(ana.id());
            snapshotTaken.countDown();
            await(release);
            return snapshot;
        }));
        assertThat(snapshotTaken.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<JournalEntry> posting = CompletableFuture.supplyAsync(() -> post(500));

        assertThat(posting).as("waits for the snapshot's transaction").isNotCompleted();
        Thread.sleep(Duration.ofMillis(500));
        assertThat(posting).isNotCompleted();
        release.countDown();
        assertThat(holder.get(10, TimeUnit.SECONDS).entryIds()).isEmpty();
        assertThat(posting.get(10, TimeUnit.SECONDS)).isNotNull();
        assertThat(ledger.balance(ana.id())).isEqualTo(eur(500));
    }

    private JournalEntry post(long amountMinor) {
        return ledger.post(new NewJournalEntry(
                group.id(),
                DAY,
                "Dinner",
                recordedBy,
                List.of(new Posting(ana.id(), eur(amountMinor)), new Posting(joao.id(), eur(-amountMinor)))));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Money eur(long minor) {
        return new Money(minor, EUR);
    }
}
