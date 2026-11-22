package io.github.muzachio.moneytrail.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A group's ledger (ADR-0012): one {@link AccountKind#GROUP_MEMBER} account per member, and an
 * expense whose payer gets two postings, what they paid and their share. Names and amounts are
 * invented.
 */
class GroupLedgersTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID recordedBy;
    private UUID groupId;
    private Ledger group;
    private Account ana;
    private Account joao;
    private Account rita;

    @BeforeEach
    void openAGroupWithThreeMembers() {
        recordedBy = LedgerTestUsers.create(jdbc);
        groupId = UUID.randomUUID();
        group = ledger.openLedger(LedgerOwner.group(groupId));
        ana = member("Ana");
        joao = member("João");
        rita = member("Rita");
    }

    @Test
    void opensAGroupsLedgerWithAMemberAccountForEachMember() {
        assertThat(group.owner()).isEqualTo(new LedgerOwner(LedgerOwner.Type.GROUP, groupId));
        assertThat(ledger.findLedger(LedgerOwner.group(groupId))).contains(group);
        assertThat(ledger.findLedger(LedgerOwner.user(groupId))).isEmpty();
        assertThat(jdbc.sql("SELECT owner_type FROM ledgers WHERE id = :id")
                        .param("id", group.id())
                        .query(String.class)
                        .single())
                .isEqualTo("GROUP");
        assertThat(ledger.accounts(group.id())).containsExactly(ana, joao, rita);
        assertThat(ledger.accounts(group.id())).extracting(Account::kind).containsOnly(AccountKind.GROUP_MEMBER);
        assertThat(jdbc.sql("SELECT DISTINCT kind FROM accounts WHERE ledger_id = :id")
                        .param("id", group.id())
                        .query(String.class)
                        .list())
                .containsExactly("GROUP_MEMBER");
        assertThat(ledger.balances(group.id()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(ana.id(), eur(0), joao.id(), eur(0), rita.id(), eur(0)));
    }

    // Unlike categories, two members of a group can be called the same.
    @Test
    void twoMembersMayHaveTheSameName() {
        Account anotherAna = member("Ana");

        assertThat(anotherAna.id()).isNotEqualTo(ana.id());
        assertThat(ledger.accounts(group.id())).extracting(Account::name).containsExactly("Ana", "João", "Rita", "Ana");
    }

    @Test
    void anExpenseKeepsThePayersTwoPostingsApart() {
        // Ana pays 30.00 for dinner, split equally: she paid 30.00 and her share is 10.00.
        List<Posting> postings =
                List.of(posting(ana, 3000), posting(ana, -1000), posting(joao, -1000), posting(rita, -1000));

        JournalEntry dinner = ledger.post(new NewJournalEntry(group.id(), DAY, "Dinner", recordedBy, postings));

        assertThat(dinner.postings()).containsExactlyElementsOf(postings);
        assertThat(ledger.findEntry(dinner.id())).contains(dinner);
        assertThat(jdbc.sql(
                                "SELECT amount_minor FROM postings WHERE entry_id = :entry AND account_id = :ana ORDER BY id")
                        .param("entry", dinner.id())
                        .param("ana", ana.id())
                        .query(Long.class)
                        .list())
                .containsExactly(3000L, -1000L);
        assertThat(ledger.balances(group.id()))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(ana.id(), eur(2000), joao.id(), eur(-1000), rita.id(), eur(-1000)));
        // The projection adds up an entry's postings per account before it writes: one update of
        // Ana's balance, not two.
        assertThat(jdbc.sql("SELECT version FROM account_balances WHERE account_id = :id")
                        .param("id", ana.id())
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @Test
    void theMembersBalancesAddUpToZeroAfterExpensesAndAPayment() {
        // Ana pays 30.00 for dinner, split equally; Rita pays 10.00 for coffee, split with João
        // only; João pays Ana back 10.00.
        post(posting(ana, 3000), posting(ana, -1000), posting(joao, -1000), posting(rita, -1000));
        post(posting(rita, 1000), posting(rita, -500), posting(joao, -500));
        post(posting(joao, 1000), posting(ana, -1000));

        Map<UUID, Money> balances = ledger.balances(group.id());

        assertThat(balances)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(ana.id(), eur(1000), joao.id(), eur(-500), rita.id(), eur(-500)));
        assertThat(balances.values().stream().mapToLong(Money::minor).sum()).isZero();
    }

    // The payer's account is in the entry twice, and post() still locks each balance once, in
    // ascending id order: waiting for the lower account, it does not hold the higher one yet;
    // waiting for the higher one, it already holds the lower one.
    @Test
    void postLocksThePayersBalanceOnceAndInAscendingAccountIdOrder() throws Exception {
        BalanceLockProbe locks = new BalanceLockProbe(ledger, jdbc, transactionManager);
        List<UUID> order = locks.inLockOrder(ana.id(), joao.id());
        UUID lower = order.get(0);
        UUID higher = order.get(1);
        // Ana pays 25.00 for lunch, half each. The postings start with the higher account, so the
        // order of the locks cannot come from the order of the postings.
        List<Posting> lunch = List.of(posting(ana, 2500), posting(ana, -1250), posting(joao, -1250));
        NewJournalEntry highestFirst = new NewJournalEntry(
                group.id(), DAY, "Lunch", recordedBy, higher.equals(ana.id()) ? lunch : lunch.reversed());

        assertThat(locks.heldWhilePostWaitsFor(lower, highestFirst)).doesNotContain(higher);
        assertThat(locks.heldWhilePostWaitsFor(higher, highestFirst)).contains(lower);
        assertThat(ledger.balances(group.id()))
                .containsEntry(ana.id(), eur(2 * 1250))
                .containsEntry(joao.id(), eur(-2 * 1250));
    }

    private Account member(String name) {
        return ledger.openAccount(new NewAccount(group.id(), AccountKind.GROUP_MEMBER, null, name, EUR));
    }

    private JournalEntry post(Posting... postings) {
        return ledger.post(new NewJournalEntry(group.id(), DAY, "Test", recordedBy, List.of(postings)));
    }

    private static Posting posting(Account account, long minor) {
        return new Posting(account.id(), eur(minor));
    }

    private static Money eur(long minor) {
        return new Money(minor, EUR);
    }
}
