package io.github.muzachio.moneytrail.groups.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.groups.internal.Balances.MemberBalance;
import io.github.muzachio.moneytrail.groups.internal.Split.MemberShare;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestContextManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0012's promise for a group's balances, against a real Postgres: after any sequence of expenses,
 * settlements, edits and reversals, each member's balance is what they paid minus their shares of the
 * expenses still in effect, plus the settlements they paid, minus the settlements they were paid; so
 * the balances add up to zero. jqwik generates the steps as plans (members by position, expenses and
 * settlements by their place among those in effect), since the people and the entries only exist
 * once a try has made them; every try gets a group of its own.
 *
 * <p>No class-level annotations here: Spring would then build a second application context, and
 * with it a second Postgres container, instead of sharing the one of the other integration tests.
 */
class GroupExpensesProperties extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 14);
    private static final int MEMBERS = 4;

    /**
     * An expense by positions: who paid, who is in the split, and a weight per member, which becomes
     * their shares, their basis points or their exact amount depending on the method.
     */
    record Plan(int payer, SplitMethod method, List<Integer> members, long amount, List<Long> weights) {}

    /** A settlement by positions: {@code to} is {@code from} plus {@code offset}, round the members. */
    record Payment(int from, int offset, long amount) {}

    sealed interface Step {}

    record Add(Plan plan) implements Step {}

    record Pay(Payment payment) implements Step {}

    /**
     * {@code target}: which expense or settlement in effect, counted round them; {@code by}: which
     * member edits it, with {@code plan} for an expense and {@code payment} for a settlement.
     */
    record Edit(int target, int by, Plan plan, Payment payment) implements Step {}

    record Reverse(int target, int by) implements Step {}

    @Autowired
    private GroupExpenses expenses;

    @Autowired
    private Settlements settlements;

    @Autowired
    private Balances balances;

    @Autowired
    private Groups groups;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private List<UUID> people;
    private UUID groupId;

    // jqwik is a test engine of its own, so Spring's JUnit Jupiter extension does not run here. This
    // is the part of it the properties need: the shared application context, injected.
    @BeforeProperty
    void injectSpringBeans() throws Exception {
        new TestContextManager(getClass()).prepareTestInstance(this);
    }

    @BeforeTry
    void groupOfFour() {
        people = new ArrayList<>();
        for (int i = 0; i < MEMBERS; i++) {
            people.add(jdbc.sql("INSERT INTO users (email, name) VALUES (:email, 'Member') RETURNING id")
                    .param("email", "properties-" + UUID.randomUUID() + "@example.com")
                    .query(UUID.class)
                    .single());
        }
        groupId = groups.create(people.getFirst(), "Trip", EUR).group().id();
        people.subList(1, MEMBERS).forEach(this::join);
    }

    @Property(tries = 50)
    void everyBalanceIsWhatTheMemberPaidMinusTheirSharesPlusWhatTheyPaidMinusWhatTheyWerePaid(
            @ForAll("steps") List<Step> steps) {
        Map<UUID, Movement> inEffect = new LinkedHashMap<>();

        for (Step step : steps) {
            switch (step) {
                case Add recording -> {
                    Expense recorded = expenses.record(people.getFirst(), groupId, expenseOf(recording.plan()));
                    inEffect.put(recorded.id(), recorded);
                }
                case Pay paying -> {
                    Settlement recorded = settlements.record(people.getLast(), groupId, settlementOf(paying.payment()));
                    inEffect.put(recorded.id(), recorded);
                }
                case Edit edit -> {
                    if (!inEffect.isEmpty()) {
                        UUID target = pick(inEffect, edit.target());
                        UUID by = people.get(edit.by());
                        Movement correction = switch (Objects.requireNonNull(inEffect.get(target))) {
                            case Expense _ ->
                                expenses.reverse(by, groupId, target, expenseOf(edit.plan()))
                                        .expense();
                            case Settlement _ ->
                                settlements
                                        .reverse(by, groupId, target, settlementOf(edit.payment()))
                                        .settlement();
                        };
                        inEffect.remove(target);
                        inEffect.put(correction.id(), correction);
                    }
                }
                case Reverse reverse -> {
                    if (!inEffect.isEmpty()) {
                        UUID target = pick(inEffect, reverse.target());
                        UUID by = people.get(reverse.by());
                        switch (Objects.requireNonNull(inEffect.get(target))) {
                            case Expense _ -> expenses.reverse(by, groupId, target, null);
                            case Settlement _ -> settlements.reverse(by, groupId, target, null);
                        }
                        inEffect.remove(target);
                    }
                }
            }
        }

        Map<UUID, Long> expected = new LinkedHashMap<>();
        people.forEach(person -> expected.put(person, 0L));
        for (Movement movement : inEffect.values()) {
            switch (movement) {
                case Expense expense -> {
                    expected.merge(expense.paidBy(), expense.amount().minor(), Long::sum);
                    for (MemberShare share : expense.split().shares()) {
                        expected.merge(share.memberId(), -share.share().minor(), Long::sum);
                    }
                }
                case Settlement settlement -> {
                    expected.merge(settlement.from(), settlement.amount().minor(), Long::sum);
                    expected.merge(settlement.to(), -settlement.amount().minor(), Long::sum);
                }
            }
        }
        expected.forEach((person, balance) ->
                assertThat(ledger.balance(accountOf(person))).isEqualTo(new Money(balance, EUR)));
        assertThat(expected.values().stream().mapToLong(Long::longValue).sum()).isZero();
        assertThat(balances.of(people.getFirst(), groupId))
                .containsExactlyInAnyOrderElementsOf(expected.entrySet().stream()
                        .map(entry -> new MemberBalance(entry.getKey(), new Money(entry.getValue(), EUR)))
                        .toList());
        assertThat(expenses.list(people.getLast(), groupId, null, 100).items())
                .extracting(Movement::id)
                .containsExactlyInAnyOrderElementsOf(inEffect.keySet());
    }

    @Provide
    Arbitrary<List<Step>> steps() {
        Arbitrary<Integer> member = Arbitraries.integers().between(0, MEMBERS - 1);
        Arbitrary<Integer> target = Arbitraries.integers().between(0, 20);
        Arbitrary<Step> records = plans().map(Add::new);
        Arbitrary<Step> payments = payments().map(Pay::new);
        Arbitrary<Step> edits =
                Combinators.combine(target, member, plans(), payments()).as(Edit::new);
        Arbitrary<Step> reversals = Combinators.combine(target, member).as(Reverse::new);
        return Arbitraries.frequencyOf(
                        Tuple.of(3, records), Tuple.of(2, payments), Tuple.of(1, edits), Tuple.of(1, reversals))
                .list()
                .ofMinSize(1)
                .ofMaxSize(12);
    }

    private static Arbitrary<Plan> plans() {
        Arbitrary<Integer> member = Arbitraries.integers().between(0, MEMBERS - 1);
        Arbitrary<List<Integer>> inTheSplit =
                member.set().ofMinSize(1).ofMaxSize(MEMBERS).map(ArrayList::new);
        return Combinators.combine(
                        member,
                        Arbitraries.of(SplitMethod.class),
                        inTheSplit,
                        Arbitraries.longs().between(1, 1_000_000),
                        Arbitraries.longs().between(1, 100).list().ofSize(MEMBERS))
                .as(Plan::new);
    }

    private static Arbitrary<Payment> payments() {
        return Combinators.combine(
                        Arbitraries.integers().between(0, MEMBERS - 1),
                        Arbitraries.integers().between(1, MEMBERS - 1),
                        Arbitraries.longs().between(1, 1_000_000))
                .as(Payment::new);
    }

    /**
     * Turns the weights into valid inputs: basis points that add up to 10 000 (each at least 25,
     * since the weights add up to 400 at most), or exact amounts whose sum is the total.
     */
    private NewExpense expenseOf(Plan plan) {
        List<SplitInput> inputs = new ArrayList<>();
        long total = plan.amount();
        long weights = plan.members().stream().mapToLong(plan.weights()::get).sum();
        long basisPointsLeft = Splits.WHOLE;
        for (int i = 0; i < plan.members().size(); i++) {
            int position = plan.members().get(i);
            long weight = plan.weights().get(position);
            long value = switch (plan.method()) {
                case EQUALLY -> 1;
                case SHARES, EXACT_AMOUNTS -> weight;
                case PERCENTAGES -> i == plan.members().size() - 1 ? basisPointsLeft : Splits.WHOLE * weight / weights;
            };
            basisPointsLeft -= value;
            inputs.add(new SplitInput(people.get(position), value));
        }
        if (plan.method() == SplitMethod.EXACT_AMOUNTS) {
            total = inputs.stream().mapToLong(SplitInput::value).sum();
        }
        return new NewExpense(new Money(total, EUR), DAY, "Expense", people.get(plan.payer()), plan.method(), inputs);
    }

    private NewSettlement settlementOf(Payment payment) {
        return new NewSettlement(
                new Money(payment.amount(), EUR),
                DAY,
                people.get(payment.from()),
                people.get((payment.from() + payment.offset()) % MEMBERS));
    }

    private static UUID pick(Map<UUID, Movement> inEffect, int target) {
        List<UUID> ids = new ArrayList<>(inEffect.keySet());
        return ids.get(target % ids.size());
    }

    /** What accepting an invite does (#87): a member account, and the member's row. */
    private void join(UUID userId) {
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

    private UUID accountOf(UUID userId) {
        return jdbc.sql("SELECT account_id FROM group_members WHERE group_id = :group AND user_id = :user")
                .param("group", groupId)
                .param("user", userId)
                .query(UUID.class)
                .single();
    }
}
