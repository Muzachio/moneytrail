package io.github.muzachio.moneytrail.groups.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.groups.internal.Split.MemberShare;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Members who record expenses at the same moment, each of them paid by one and split among all, so
 * every expense posts to every member account: each one locks the group and the member rows {@code
 * FOR SHARE} and the balances {@code FOR UPDATE}, which must queue up instead of deadlocking.
 */
class GroupExpensesConcurrencyTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final int MEMBERS = 4;
    private static final int EXPENSES_PER_MEMBER = 20;

    @Autowired
    private GroupExpenses expenses;

    @Autowired
    private Groups groups;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void membersRecordingExpensesAtOnceNeverDeadlockAndEveryBalanceIsExact() throws Exception {
        List<UUID> people = new ArrayList<>();
        for (int i = 0; i < MEMBERS; i++) {
            people.add(newUser());
        }
        UUID groupId =
                groups.create(people.getFirst(), "Shared house", EUR).group().id();
        people.subList(1, MEMBERS).forEach(person -> join(groupId, person));
        Map<UUID, Long> expected = new ConcurrentHashMap<>();
        CyclicBarrier allReady = new CyclicBarrier(MEMBERS);
        ExecutorService threads = Executors.newFixedThreadPool(MEMBERS);

        try {
            List<Future<?>> members = new ArrayList<>();
            for (int thread = 0; thread < MEMBERS; thread++) {
                UUID payer = people.get(thread);
                // A different amount per member and expense: a lost update could not cancel out unnoticed.
                long base = 1000L + 37L * thread;
                members.add(threads.submit(() -> {
                    allReady.await(30, TimeUnit.SECONDS);
                    for (int i = 0; i < EXPENSES_PER_MEMBER; i++) {
                        Expense recorded = expenses.record(
                                payer,
                                groupId,
                                new NewExpense(
                                        new Money(base + i, EUR),
                                        LocalDate.of(2026, 11, 14),
                                        "Groceries",
                                        payer,
                                        SplitMethod.EQUALLY,
                                        people.stream()
                                                .map(person -> new SplitInput(person, 1))
                                                .toList()));
                        expected.merge(payer, recorded.amount().minor(), Long::sum);
                        for (MemberShare share : recorded.split().shares()) {
                            expected.merge(share.memberId(), -share.share().minor(), Long::sum);
                        }
                    }
                    return null;
                }));
            }
            // get() rethrows whatever a thread's record() threw, a deadlock included.
            for (Future<?> member : members) {
                member.get(2, TimeUnit.MINUTES);
            }
        } finally {
            threads.shutdownNow();
        }

        for (UUID person : people) {
            assertThat(ledger.balance(accountOf(groupId, person)))
                    .isEqualTo(new Money(expected.getOrDefault(person, 0L), EUR));
        }
        assertThat(expected.values().stream().mapToLong(Long::longValue).sum()).isZero();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM journal_entries
                        WHERE ledger_id = (SELECT ledger_id FROM groups WHERE id = :group)""").param("group", groupId).query(Long.class).single())
                .isEqualTo((long) MEMBERS * EXPENSES_PER_MEMBER);
    }

    private UUID newUser() {
        return jdbc.sql("INSERT INTO users (email, name) VALUES (:email, 'Member') RETURNING id")
                .param("email", "concurrency-" + UUID.randomUUID() + "@example.com")
                .query(UUID.class)
                .single();
    }

    /** What accepting an invite will do (#87): a member account, and the member's row. */
    private void join(UUID groupId, UUID userId) {
        transactions.executeWithoutResult(status -> {
            UUID ledgerId =
                    ledger.findLedger(LedgerOwner.group(groupId)).orElseThrow().id();
            UUID account = ledger.openAccount(
                            new NewAccount(ledgerId, AccountKind.GROUP_MEMBER, null, Groups.MEMBER_ACCOUNT, EUR))
                    .id();
            jdbc.sql("INSERT INTO group_members (group_id, user_id, account_id) VALUES (:group, :user, :account)")
                    .param("group", groupId)
                    .param("user", userId)
                    .param("account", account)
                    .update();
        });
    }

    private UUID accountOf(UUID groupId, UUID userId) {
        return jdbc.sql("SELECT account_id FROM group_members WHERE group_id = :group AND user_id = :user")
                .param("group", groupId)
                .param("user", userId)
                .query(UUID.class)
                .single();
    }
}
