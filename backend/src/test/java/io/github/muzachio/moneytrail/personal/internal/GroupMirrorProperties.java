package io.github.muzachio.moneytrail.personal.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.groups.ExpenseCategory;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.Ledger;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.personal.internal.GroupMirrorStore.EntryType;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.BeforeTry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestContextManager;

/**
 * ADR-0015's promise, against a real Postgres: after any sequence of expenses, settlements, edits and
 * reversals in a group, with the mirror turned on at any point, and with each event delivered once or
 * twice in any order (as at-least-once delivery after a failure can), the group's account in the
 * member's ledger has exactly the member's balance in the group.
 *
 * <p>The group's entries are posted straight to the ledger, the way the groups module posts them
 * (ADR-0012), and the events are handed to {@link GroupMirrors} as the listener would; the listener and
 * the groups module have their own tests. No class-level annotations: see GroupExpensesProperties.
 */
class GroupMirrorProperties extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 28);
    private static final int MEMBERS = 3;

    /** By positions: who paid, which members share it equally (a bit per member, never none), how much. */
    record Plan(int payer, int members, long amount, boolean settlement) {}

    sealed interface Step {}

    record Add(Plan plan) implements Step {}

    /** {@code target}: which entry in effect, counted round them. */
    record Edit(int target, Plan plan) implements Step {}

    record Reverse(int target) implements Step {}

    sealed interface Event {}

    record Recorded(EntryType type, UUID id) implements Event {}

    record Corrected(EntryType type, UUID replacedId, UUID replacementId) implements Event {}

    record Reversed(EntryType type, UUID id) implements Event {}

    /** An entry in effect: its type and its version in effect. */
    record InEffect(EntryType type, UUID id) {}

    @Autowired
    private GroupMirrors mirrors;

    @Autowired
    private PersonalLedgers personalLedgers;

    @Autowired
    private UserAccounts userAccounts;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    private List<UUID> people;
    private List<UUID> memberAccounts;
    private UUID groupId;
    private UUID groupLedgerId;

    @BeforeProperty
    void injectSpringBeans() throws Exception {
        new TestContextManager(getClass()).prepareTestInstance(this);
    }

    @BeforeTry
    void groupOfThree() {
        groupId = UUID.randomUUID();
        Ledger groupLedger = ledger.openLedger(LedgerOwner.group(groupId));
        groupLedgerId = groupLedger.id();
        people = new ArrayList<>();
        memberAccounts = new ArrayList<>();
        for (int i = 0; i < MEMBERS; i++) {
            people.add(jdbc.sql("INSERT INTO users (email, name) VALUES (:email, 'Member') RETURNING id")
                    .param("email", "mirror-properties-" + UUID.randomUUID() + "@example.com")
                    .query(UUID.class)
                    .single());
            memberAccounts.add(
                    ledger.openAccount(new NewAccount(groupLedgerId, AccountKind.GROUP_MEMBER, null, "Member", EUR))
                            .id());
        }
        jdbc.sql("""
                        INSERT INTO groups (id, name, currency, ledger_id, created_by)
                        VALUES (:id, 'Trip', 'EUR', :ledgerId, :createdBy)""")
                .param("id", groupId)
                .param("ledgerId", groupLedgerId)
                .param("createdBy", people.getFirst())
                .update();
        for (int i = 0; i < MEMBERS; i++) {
            jdbc.sql("INSERT INTO group_members (group_id, user_id, account_id) VALUES (:groupId, :userId, :accountId)")
                    .param("groupId", groupId)
                    .param("userId", people.get(i))
                    .param("accountId", memberAccounts.get(i))
                    .update();
        }
    }

    @Property(tries = 40)
    void theGroupAccountAlwaysHoldsTheMembersBalanceInTheGroup(
            @ForAll("steps") List<Step> steps,
            @ForAll("turnOnPoint") int turnOnAt,
            @ForAll("seeds") long deliverySeed) {
        UUID member = people.getFirst();
        UUID ledgerId = personalLedgers.ledgerOf(member);
        UUID checking = userAccounts
                .create(member, UserAccount.Type.CHECKING, "Checking", EUR, null)
                .id();
        UUID fallback = ledger.accounts(ledgerId).stream()
                .filter(account -> account.kind() == AccountKind.EXPENSE && "other".equals(account.subtype()))
                .findFirst()
                .orElseThrow()
                .id();

        List<InEffect> inEffect = new ArrayList<>();
        List<Event> events = new ArrayList<>();
        int at = Math.min(turnOnAt, steps.size());
        for (int i = 0; i < steps.size(); i++) {
            if (i == at) {
                mirrors.turnOn(member, groupId, fallback, checking);
            }
            apply(steps.get(i), inEffect, events);
        }
        if (at == steps.size()) {
            mirrors.turnOn(member, groupId, fallback, checking);
        }

        Random random = new Random(deliverySeed);
        List<Event> deliveries = new ArrayList<>(events);
        events.stream().filter(event -> random.nextBoolean()).forEach(deliveries::add);
        Collections.shuffle(deliveries, random);
        deliveries.forEach(this::deliver);

        UUID groupAccount = mirrors.find(member, groupId).orElseThrow().accountId();
        assertThat(ledger.balance(groupAccount)).isEqualTo(ledger.balance(memberAccounts.getFirst()));
    }

    private void apply(Step step, List<InEffect> inEffect, List<Event> events) {
        switch (step) {
            case Add(Plan plan) -> {
                JournalEntry entry = ledger.post(entryOf(plan));
                EntryType type = typeOf(plan);
                inEffect.add(new InEffect(type, entry.id()));
                events.add(new Recorded(type, entry.id()));
            }
            case Edit(int target, Plan plan) -> {
                if (inEffect.isEmpty()) {
                    return;
                }
                int index = target % inEffect.size();
                InEffect old = inEffect.get(index);
                // An edit keeps what the entry is: an expense stays an expense.
                Plan same = new Plan(plan.payer(), plan.members(), plan.amount(), old.type() == EntryType.SETTLEMENT);
                JournalEntry replacement = ledger.replace(old.id(), entryOf(same));
                inEffect.set(index, new InEffect(old.type(), replacement.id()));
                events.add(new Corrected(old.type(), old.id(), replacement.id()));
            }
            case Reverse(int target) -> {
                if (inEffect.isEmpty()) {
                    return;
                }
                InEffect old = inEffect.remove(target % inEffect.size());
                ledger.reverse(old.id(), people.getFirst());
                events.add(new Reversed(old.type(), old.id()));
            }
        }
    }

    private void deliver(Event event) {
        switch (event) {
            case Recorded(EntryType type, UUID id) -> mirrors.recorded(groupId, id, type, categoryOf(type), "Spent");
            case Corrected(EntryType type, UUID replacedId, UUID replacementId) ->
                mirrors.corrected(groupId, replacedId, replacementId, type, categoryOf(type), "Spent again");
            case Reversed(EntryType type, UUID id) -> mirrors.reversed(groupId, id, type);
        }
    }

    private static @Nullable ExpenseCategory categoryOf(EntryType type) {
        return type == EntryType.EXPENSE ? ExpenseCategory.FOOD : null;
    }

    private static EntryType typeOf(Plan plan) {
        return plan.settlement() ? EntryType.SETTLEMENT : EntryType.EXPENSE;
    }

    /**
     * An expense: the payer {@code +amount}, each member in the split {@code −share}, equal shares with
     * the cents left over to the first ones. A settlement: the payer {@code +amount} and the next member
     * round {@code −amount}.
     */
    private NewJournalEntry entryOf(Plan plan) {
        int payer = plan.payer() % MEMBERS;
        Money total = new Money(plan.amount(), EUR);
        List<Posting> postings = new ArrayList<>();
        postings.add(new Posting(memberAccounts.get(payer), total));
        if (plan.settlement()) {
            postings.add(new Posting(memberAccounts.get((payer + 1) % MEMBERS), total.negate()));
        } else {
            List<Integer> split = new ArrayList<>();
            for (int i = 0; i < MEMBERS; i++) {
                if ((plan.members() & (1 << i)) != 0) {
                    split.add(i);
                }
            }
            long each = plan.amount() / split.size();
            long left = plan.amount() % split.size();
            for (int i = 0; i < split.size(); i++) {
                long share = each + (i < left ? 1 : 0);
                if (share > 0) {
                    postings.add(new Posting(memberAccounts.get(split.get(i)), new Money(-share, EUR)));
                }
            }
        }
        return new NewJournalEntry(groupLedgerId, DAY, "Group entry", people.get(payer), postings);
    }

    @Provide
    Arbitrary<List<Step>> steps() {
        Arbitrary<Plan> plans = Combinators.combine(
                        Arbitraries.integers().between(0, MEMBERS - 1),
                        Arbitraries.integers().between(1, (1 << MEMBERS) - 1),
                        Arbitraries.longs().between(1, 10_000),
                        Arbitraries.integers().between(0, 3).map(n -> n == 0))
                .as(Plan::new);
        Arbitrary<Step> step = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(4, plans.map(Add::new)),
                net.jqwik.api.Tuple.of(
                        2,
                        Combinators.combine(Arbitraries.integers().between(0, 20), plans)
                                .as(Edit::new)),
                net.jqwik.api.Tuple.of(1, Arbitraries.integers().between(0, 20).map(Reverse::new)));
        return step.list().ofMinSize(1).ofMaxSize(10);
    }

    @Provide
    Arbitrary<Integer> turnOnPoint() {
        return Arbitraries.integers().between(0, 10);
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs();
    }
}
