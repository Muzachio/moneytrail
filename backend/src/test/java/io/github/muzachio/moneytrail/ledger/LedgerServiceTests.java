package io.github.muzachio.moneytrail.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.shared.DomainException;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class LedgerServiceTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    private UUID userId;
    private Ledger personal;

    @BeforeEach
    void openPersonalLedger() {
        userId = LedgerTestUsers.create(jdbc);
        personal = ledger.openLedger(LedgerOwner.user(userId));
    }

    @Test
    void postsAnEntryAndReadsItBack() {
        Account food = ledger.openAccount(new NewAccount(personal.id(), AccountKind.EXPENSE, null, "Food", EUR));
        Account checking =
                ledger.openAccount(new NewAccount(personal.id(), AccountKind.ASSET, "CHECKING", "Checking", EUR));
        List<Posting> postings = List.of(
                new Posting(food.id(), new Money(1250, EUR)), new Posting(checking.id(), new Money(-1250, EUR)));

        JournalEntry posted =
                ledger.post(new NewJournalEntry(personal.id(), LocalDate.of(2026, 11, 8), "Lunch", userId, postings));

        assertThat(posted.id().version()).isEqualTo(7);
        assertThat(posted)
                .isEqualTo(new JournalEntry(
                        posted.id(), personal.id(), LocalDate.of(2026, 11, 8), "Lunch", userId, postings));
        assertThat(ledger.findEntry(posted.id())).contains(posted);
        assertThat(jdbc.sql("SELECT currency || ' ' || amount_minor FROM postings WHERE entry_id = :id ORDER BY id")
                        .param("id", posted.id())
                        .query(String.class)
                        .list())
                .containsExactly("EUR 1250", "EUR -1250");
    }

    @ParameterizedTest
    @EnumSource(AccountKind.class)
    void opensAnAccountOfEachKind(AccountKind kind) {
        Account account = ledger.openAccount(new NewAccount(personal.id(), kind, null, "Some " + kind, EUR));

        assertThat(account.kind()).isEqualTo(kind);
        assertThat(jdbc.sql("SELECT kind FROM accounts WHERE id = :id")
                        .param("id", account.id())
                        .query(String.class)
                        .single())
                .isEqualTo(kind.name());
    }

    @Test
    void opensAccountsOnlyInLedgersThatExist() {
        NewAccount orphan = new NewAccount(UUID.randomUUID(), AccountKind.ASSET, null, "Checking", EUR);

        assertThatThrownBy(() -> ledger.openAccount(orphan))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ledger_not_found"));
    }

    @Test
    void refusesAnAccountOfAnotherLedgerAndWritesNothing() {
        Account food = ledger.openAccount(new NewAccount(personal.id(), AccountKind.EXPENSE, null, "Food", EUR));
        Ledger someoneElses = ledger.openLedger(LedgerOwner.user(LedgerTestUsers.create(jdbc)));
        Account theirChecking =
                ledger.openAccount(new NewAccount(someoneElses.id(), AccountKind.ASSET, null, "Checking", EUR));
        NewJournalEntry lunch = new NewJournalEntry(
                personal.id(),
                LocalDate.of(2026, 11, 8),
                "Lunch",
                userId,
                List.of(
                        new Posting(food.id(), new Money(1250, EUR)),
                        new Posting(theirChecking.id(), new Money(-1250, EUR))));

        assertThatThrownBy(() -> ledger.post(lunch))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("account_not_in_ledger"));
        assertThat(jdbc.sql("SELECT count(*) FROM journal_entries WHERE ledger_id IN (:mine, :theirs)")
                        .param("mine", personal.id())
                        .param("theirs", someoneElses.id())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void findsNoEntryForAnUnknownId() {
        assertThat(ledger.findEntry(UUID.randomUUID())).isEmpty();
    }
}
