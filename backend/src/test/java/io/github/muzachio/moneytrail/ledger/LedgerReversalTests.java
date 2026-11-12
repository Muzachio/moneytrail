package io.github.muzachio.moneytrail.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.shared.DomainException;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link LedgerService#reverse}, {@link LedgerService#replace} and {@link LedgerService#history}. */
class LedgerReversalTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 22);

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    private UUID userId;
    private Ledger personal;
    private Account food;
    private Account leisure;
    private Account card;

    @BeforeEach
    void openAccounts() {
        userId = LedgerTestUsers.create(jdbc);
        personal = ledger.openLedger(LedgerOwner.user(userId));
        food = ledger.openAccount(new NewAccount(personal.id(), AccountKind.EXPENSE, null, "Food", EUR));
        leisure = ledger.openAccount(new NewAccount(personal.id(), AccountKind.EXPENSE, null, "Leisure", EUR));
        card = ledger.openAccount(new NewAccount(personal.id(), AccountKind.LIABILITY, null, "Card", EUR));
    }

    @Test
    void aReversalIsTheExactOppositeOnTheSameDayLinkedToTheEntry() {
        JournalEntry dinner = expense(food, 2570, DAY, "Dinner out");
        UUID reverser = LedgerTestUsers.create(jdbc);

        JournalEntry reversal = ledger.reverse(dinner.id(), reverser);

        assertThat(reversal.reversesEntryId()).isEqualTo(dinner.id());
        assertThat(reversal.replacesEntryId()).isNull();
        assertThat(reversal.occurredOn()).isEqualTo(DAY);
        assertThat(reversal.description()).isEqualTo("Dinner out");
        assertThat(reversal.createdBy()).isEqualTo(reverser);
        assertThat(reversal.postings())
                .containsExactly(new Posting(food.id(), eur(-2570)), new Posting(card.id(), eur(2570)));
        assertThat(ledger.findEntry(reversal.id())).contains(reversal);
        assertThat(ledger.balance(food.id())).isEqualTo(eur(0));
        assertThat(ledger.balance(card.id())).isEqualTo(eur(0));
        assertThat(ledger.changes(personal.id(), DAY.withDayOfMonth(1), DAY.withDayOfMonth(31)))
                .as("the month's sums are as if the entry had never been posted")
                .isEmpty();
    }

    @Test
    void anEntryIsReversedOnlyOnce() {
        JournalEntry dinner = expense(food, 2570, DAY, "Dinner out");
        JournalEntry reversal = ledger.reverse(dinner.id(), userId);

        assertRefused(() -> ledger.reverse(dinner.id(), userId), "entry_already_reversed");
        assertRefused(
                () -> ledger.replace(dinner.id(), expenseOf(food, 2750, DAY, "Dinner out")), "entry_already_reversed");
        assertRefused(() -> ledger.reverse(reversal.id(), userId), "entry_is_a_reversal");
        assertRefused(() -> ledger.reverse(UUID.randomUUID(), userId), "entry_not_found");
        assertThat(entriesInLedger()).isEqualTo(2);
        assertThat(ledger.balance(card.id())).isEqualTo(eur(0));
    }

    @Test
    void aReplacementIsPostedWithTheReversalOfTheEntryItCorrects() {
        JournalEntry dinner = expense(food, 2570, DAY, "Dinner out");

        JournalEntry correction = ledger.replace(dinner.id(), expenseOf(leisure, 2750, DAY.plusDays(1), "Cinema"));

        assertThat(correction.replacesEntryId()).isEqualTo(dinner.id());
        assertThat(correction.reversesEntryId()).isNull();
        assertThat(correction.occurredOn()).isEqualTo(DAY.plusDays(1));
        List<JournalEntry> history = ledger.history(dinner.id());
        assertThat(history).hasSize(3);
        assertThat(history.get(0)).isEqualTo(dinner);
        assertThat(history.get(1).reversesEntryId()).isEqualTo(dinner.id());
        assertThat(history.get(1).postings())
                .containsExactly(new Posting(food.id(), eur(-2570)), new Posting(card.id(), eur(2570)));
        assertThat(history.get(2)).isEqualTo(correction);
        assertThat(ledger.balance(food.id())).isEqualTo(eur(0));
        assertThat(ledger.balance(leisure.id())).isEqualTo(eur(2750));
        assertThat(ledger.balance(card.id())).isEqualTo(eur(-2750));
    }

    @Test
    void everyEntryOfAHistoryHasTheSameHistory() {
        JournalEntry first = expense(food, 2570, DAY, "Dinner out");
        JournalEntry second = ledger.replace(first.id(), expenseOf(food, 2750, DAY, "Dinner out"));
        JournalEntry third = ledger.replace(second.id(), expenseOf(food, 2705, DAY, "Dinner out"));
        JournalEntry lastReversal = ledger.reverse(third.id(), userId);

        List<JournalEntry> history = ledger.history(first.id());

        assertThat(history).hasSize(6);
        assertThat(history)
                .extracting(JournalEntry::id)
                .containsSubsequence(first.id(), second.id(), third.id(), lastReversal.id());
        assertThat(history.get(1).reversesEntryId()).isEqualTo(first.id());
        assertThat(history.get(3).reversesEntryId()).isEqualTo(second.id());
        assertThat(history.get(5).reversesEntryId()).isEqualTo(third.id());
        for (JournalEntry entry : history) {
            assertThat(ledger.history(entry.id())).isEqualTo(history);
        }
        assertThat(ledger.history(expense(food, 100, DAY, "Snack").id())).hasSize(1);
        assertThat(ledger.history(UUID.randomUUID())).isEmpty();
        assertThat(ledger.balance(food.id())).isEqualTo(eur(100));
    }

    @Test
    void whenTheReplacementIsRefusedTheEntryIsNotReversedEither() {
        JournalEntry dinner = expense(food, 2570, DAY, "Dinner out");
        Ledger someoneElses = ledger.openLedger(LedgerOwner.user(LedgerTestUsers.create(jdbc)));
        Account theirFood =
                ledger.openAccount(new NewAccount(someoneElses.id(), AccountKind.EXPENSE, null, "Food", EUR));

        assertRefused(
                () -> ledger.replace(
                        dinner.id(),
                        new NewJournalEntry(
                                someoneElses.id(),
                                DAY,
                                "Dinner out",
                                userId,
                                List.of(new Posting(theirFood.id(), eur(1)), new Posting(theirFood.id(), eur(-1))))),
                "replacement_in_another_ledger");
        assertRefused(
                () -> ledger.replace(
                        dinner.id(),
                        new NewJournalEntry(
                                personal.id(),
                                DAY,
                                "Dinner out",
                                userId,
                                List.of(new Posting(food.id(), eur(2750)), new Posting(card.id(), eur(-2570))))),
                "unbalanced_entry");
        assertRefused(
                () -> ledger.replace(
                        dinner.id(),
                        new NewJournalEntry(
                                personal.id(),
                                DAY,
                                "Dinner out",
                                userId,
                                List.of(new Posting(theirFood.id(), eur(2750)), new Posting(card.id(), eur(-2750))))),
                "account_not_in_ledger");

        assertThat(ledger.history(dinner.id())).containsExactly(dinner);
        assertThat(ledger.balance(card.id())).isEqualTo(eur(-2570));
    }

    /**
     * Both threads pass the check for an earlier reversal before either has committed. The unique
     * reverses_entry_id then lets only one of them in, and the other gets the same answer as the
     * check would have given it.
     */
    @Test
    void ofTwoConcurrentReversalsOnlyOneIsPosted() throws Exception {
        int threads = 4;
        JournalEntry dinner = expense(food, 2570, DAY, "Dinner out");
        CyclicBarrier allReady = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<String> outcomes = new ArrayList<>();

        try {
            List<Future<String>> reversals = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                reversals.add(pool.submit(() -> {
                    allReady.await(30, TimeUnit.SECONDS);
                    try {
                        ledger.reverse(dinner.id(), userId);
                        return "reversed";
                    } catch (DomainException e) {
                        return e.code();
                    }
                }));
            }
            for (Future<String> reversal : reversals) {
                outcomes.add(reversal.get(2, TimeUnit.MINUTES));
            }
        } catch (ExecutionException e) {
            throw new AssertionError("A reversal failed with something else than a refusal", e.getCause());
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).containsOnlyOnce("reversed").containsOnly("reversed", "entry_already_reversed");
        assertThat(entriesInLedger()).isEqualTo(2);
        assertThat(ledger.balance(card.id())).isEqualTo(eur(0));
    }

    private JournalEntry expense(Account category, long minor, LocalDate day, String description) {
        return ledger.post(expenseOf(category, minor, day, description));
    }

    private NewJournalEntry expenseOf(Account category, long minor, LocalDate day, String description) {
        return new NewJournalEntry(
                personal.id(),
                day,
                description,
                userId,
                List.of(new Posting(category.id(), eur(minor)), new Posting(card.id(), eur(-minor))));
    }

    private long entriesInLedger() {
        return jdbc.sql("SELECT count(*) FROM journal_entries WHERE ledger_id = :ledgerId")
                .param("ledgerId", personal.id())
                .query(Long.class)
                .single();
    }

    private static void assertRefused(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static Money eur(long minor) {
        return new Money(minor, EUR);
    }
}
