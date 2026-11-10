package io.github.muzachio.moneytrail.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class LedgerConcurrencyTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final int THREADS = 8;
    private static final int ENTRIES_PER_THREAD = 25;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    /**
     * Half of the threads move money from checking to savings and name checking first; the other
     * half do the opposite. A ledger that locked balances in the order of the postings would
     * deadlock here: Postgres then aborts one of the two transactions, and its post() throws.
     */
    @Test
    void transfersInOppositeDirectionsNeverDeadlockAndEveryBalanceIsExact() throws Exception {
        UUID userId = LedgerTestUsers.create(jdbc);
        Ledger personal = ledger.openLedger(LedgerOwner.user(userId));
        Account checking = ledger.openAccount(new NewAccount(personal.id(), AccountKind.ASSET, null, "Checking", EUR));
        Account savings = ledger.openAccount(new NewAccount(personal.id(), AccountKind.ASSET, null, "Savings", EUR));
        CyclicBarrier allReady = new CyclicBarrier(THREADS);
        ExecutorService threads = Executors.newFixedThreadPool(THREADS);
        long expectedInSavings = 0;

        try {
            List<Future<?>> transfers = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                boolean toSavings = thread % 2 == 0;
                Account from = toSavings ? checking : savings;
                Account to = toSavings ? savings : checking;
                // A different amount per thread: a lost update could not cancel out unnoticed.
                long amount = 100L + thread;
                expectedInSavings += (toSavings ? amount : -amount) * ENTRIES_PER_THREAD;
                transfers.add(threads.submit(() -> {
                    allReady.await(30, TimeUnit.SECONDS);
                    for (int entry = 0; entry < ENTRIES_PER_THREAD; entry++) {
                        ledger.post(new NewJournalEntry(
                                personal.id(),
                                LocalDate.of(2026, 11, 8),
                                "Transfer",
                                userId,
                                List.of(
                                        new Posting(from.id(), new Money(-amount, EUR)),
                                        new Posting(to.id(), new Money(amount, EUR)))));
                    }
                    return null;
                }));
            }
            // get() rethrows whatever a thread's post() threw, a deadlock included.
            for (Future<?> transfer : transfers) {
                transfer.get(2, TimeUnit.MINUTES);
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(ledger.balance(savings.id())).isEqualTo(new Money(expectedInSavings, EUR));
        assertThat(ledger.balance(checking.id())).isEqualTo(new Money(-expectedInSavings, EUR));
        assertThat(sumOfPostings(savings)).isEqualTo(expectedInSavings);
        assertThat(sumOfPostings(checking)).isEqualTo(-expectedInSavings);
        assertThat(jdbc.sql("SELECT count(*) FROM journal_entries WHERE ledger_id = :ledgerId")
                        .param("ledgerId", personal.id())
                        .query(Long.class)
                        .single())
                .isEqualTo((long) THREADS * ENTRIES_PER_THREAD);
    }

    private long sumOfPostings(Account account) {
        return jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM postings WHERE account_id = :id")
                .param("id", account.id())
                .query(Long.class)
                .single();
    }
}
