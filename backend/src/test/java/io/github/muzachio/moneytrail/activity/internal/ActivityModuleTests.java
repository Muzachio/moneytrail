package io.github.muzachio.moneytrail.activity.internal;

import static io.github.muzachio.moneytrail.groups.ExpenseCategory.FOOD;
import static io.github.muzachio.moneytrail.groups.ExpenseCategory.OTHER;
import static io.github.muzachio.moneytrail.groups.ExpenseCategory.TRANSPORT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.muzachio.moneytrail.PostgresTestcontainersConfiguration;
import io.github.muzachio.moneytrail.activity.internal.ActivityStore.Stored;
import io.github.muzachio.moneytrail.groups.ExpenseCategory;
import io.github.muzachio.moneytrail.groups.GroupCreated;
import io.github.muzachio.moneytrail.groups.GroupExpenseAdded;
import io.github.muzachio.moneytrail.groups.GroupExpenseEdited;
import io.github.muzachio.moneytrail.groups.GroupExpenseReversed;
import io.github.muzachio.moneytrail.groups.GroupMembership;
import io.github.muzachio.moneytrail.groups.MemberJoined;
import io.github.muzachio.moneytrail.groups.MemberLeft;
import io.github.muzachio.moneytrail.groups.SettlementEdited;
import io.github.muzachio.moneytrail.groups.SettlementRecorded;
import io.github.muzachio.moneytrail.groups.SettlementReversed;
import io.github.muzachio.moneytrail.identity.UserProfiles;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The activity module on its own (Spring Modulith's default, standalone): each event of the groups
 * module, published in a transaction as the groups module does, becomes one item once that
 * transaction commits, through the outbox. The groups module itself is not running here, so the test
 * makes the rows the items point to.
 */
@ApplicationModuleTest
@Import(PostgresTestcontainersConfiguration.class)
class ActivityModuleTests {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    /** The groups module's API, which only the feed's endpoint asks; it has no part in the listener. */
    @MockitoBean
    private GroupMembership membership;

    @MockitoBean
    private UserProfiles profiles;

    @Autowired
    private ActivityStore store;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ApplicationEventPublisher events;

    private UUID ana;
    private UUID joao;
    private UUID group;

    // Postgres keeps microseconds, so the times are written as they come back.
    private final Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @BeforeEach
    void groupOfAnaAndJoao() {
        ana = newUser("Ana");
        joao = newUser("João");
        group = newGroup(ana);
    }

    @Test
    void aGroupCreatedIsAnItemWithTheGroupsName(Scenario scenario) {
        scenario.publish(new GroupCreated(group, "Porto weekend", ana, at))
                .andWaitForStateChange(this::items, items -> !items.isEmpty())
                .andVerify(items -> assertThat(items).singleElement().satisfies(item -> {
                    assertThat(item)
                            .isEqualTo(Activity.of(group, ActivityKind.GROUP_CREATED, ana, at, group)
                                    .withGroupName("Porto weekend"));
                }));
    }

    @Test
    void aMemberJoinedIsAnItemOfThatMember(Scenario scenario) {
        scenario.publish(new MemberJoined(group, joao, at))
                .andWaitForStateChange(this::items, items -> !items.isEmpty())
                .andVerify(items -> assertThat(items)
                        .containsExactly(Activity.of(group, ActivityKind.MEMBER_JOINED, joao, at, joao)));
    }

    @Test
    void aMemberLeftIsAnItemOfThatMember(Scenario scenario) {
        scenario.publish(new MemberLeft(group, joao, at))
                .andWaitForStateChange(this::items, items -> !items.isEmpty())
                .andVerify(items -> assertThat(items)
                        .containsExactly(Activity.of(group, ActivityKind.MEMBER_LEFT, joao, at, joao)));
    }

    /** Who recorded it is the actor, and may be someone other than who paid. */
    @Test
    void anExpenseAddedKeepsItsDescriptionCategoryAmountPayerAndDay(Scenario scenario) {
        UUID expense = UUID.randomUUID();
        scenario.publish(new GroupExpenseAdded(group, expense, "Dinner", FOOD, eur(3000), DAY, joao, ana, at))
                .andWaitForStateChange(this::items, items -> !items.isEmpty())
                .andVerify(items -> assertThat(items)
                        .containsExactly(Activity.of(group, ActivityKind.EXPENSE_ADDED, ana, at, expense)
                                .withExpense("Dinner", FOOD, eur(3000), joao, DAY)));
    }

    /** The table checks the category against the same keys as the groups module (V18, V20). */
    @Test
    void keepsAnExpenseOfEveryCategory() {
        transactions.executeWithoutResult(status -> {
            for (ExpenseCategory category : ExpenseCategory.values()) {
                events.publishEvent(new GroupExpenseAdded(
                        group, UUID.randomUUID(), category.key(), category, eur(100), DAY, ana, ana, at));
            }
        });

        await().atMost(Duration.ofSeconds(10)).until(() -> items().size() == ExpenseCategory.values().length);
        assertThat(items()).extracting(Activity::category).containsExactlyInAnyOrder(ExpenseCategory.values());
    }

    @Test
    void anExpenseEditedKeepsTheNewVersionAndWhatItReplaced(Scenario scenario) {
        UUID replaced = UUID.randomUUID();
        UUID expense = UUID.randomUUID();
        scenario.publish(new GroupExpenseEdited(
                        group,
                        expense,
                        replaced,
                        "Dinner and wine",
                        FOOD,
                        eur(3600),
                        DAY,
                        ana,
                        "Dinner",
                        OTHER,
                        eur(3000),
                        joao,
                        at))
                .andWaitForStateChange(this::items, items -> !items.isEmpty())
                .andVerify(items -> assertThat(items)
                        .containsExactly(Activity.of(group, ActivityKind.EXPENSE_EDITED, joao, at, expense)
                                .withExpense("Dinner and wine", FOOD, eur(3600), ana, DAY)
                                .withPrevious("Dinner", OTHER, eur(3000))));
    }

    @Test
    void anExpenseReversedKeepsTheVersionReversed(Scenario scenario) {
        UUID expense = UUID.randomUUID();
        scenario.publish(new GroupExpenseReversed(group, expense, "Taxi", TRANSPORT, eur(1200), joao, at))
                .andWaitForStateChange(this::items, items -> !items.isEmpty())
                .andVerify(items -> assertThat(items)
                        .containsExactly(Activity.of(group, ActivityKind.EXPENSE_REVERSED, joao, at, expense)
                                .withExpense("Taxi", TRANSPORT, eur(1200), null, null)));
    }

    @Test
    void aSettlementRecordedKeepsWhoPaidWhomHowMuchAndWhen(Scenario scenario) {
        UUID settlement = UUID.randomUUID();
        scenario.publish(new SettlementRecorded(group, settlement, eur(1500), DAY, joao, ana, ana, at))
                .andWaitForStateChange(this::items, items -> !items.isEmpty())
                .andVerify(items -> assertThat(items)
                        .containsExactly(Activity.of(group, ActivityKind.SETTLEMENT_RECORDED, ana, at, settlement)
                                .withSettlement(eur(1500), joao, ana, DAY)));
    }

    @Test
    void aSettlementEditedKeepsTheNewVersionAndTheAmountItReplaced(Scenario scenario) {
        UUID replaced = UUID.randomUUID();
        UUID settlement = UUID.randomUUID();
        scenario.publish(new SettlementEdited(
                        group, settlement, replaced, eur(1800), DAY, joao, ana, eur(1500), joao, at))
                .andWaitForStateChange(this::items, items -> !items.isEmpty())
                .andVerify(items -> assertThat(items)
                        .containsExactly(Activity.of(group, ActivityKind.SETTLEMENT_EDITED, joao, at, settlement)
                                .withSettlement(eur(1800), joao, ana, DAY)
                                .withPrevious(null, null, eur(1500))));
    }

    @Test
    void aSettlementReversedKeepsTheVersionReversed(Scenario scenario) {
        UUID settlement = UUID.randomUUID();
        scenario.publish(new SettlementReversed(group, settlement, eur(1800), joao, ana, ana, at))
                .andWaitForStateChange(this::items, items -> !items.isEmpty())
                .andVerify(items -> assertThat(items)
                        .containsExactly(Activity.of(group, ActivityKind.SETTLEMENT_REVERSED, ana, at, settlement)
                                .withSettlement(eur(1800), joao, ana, null)));
    }

    /**
     * The outbox delivers at least once. Here the same event is published twice in one transaction, so
     * the listener gets it twice, possibly at the same time: one item, and both publications complete.
     */
    @Test
    void anEventDeliveredTwiceLeavesOneItem() {
        GroupExpenseAdded event =
                new GroupExpenseAdded(group, UUID.randomUUID(), "Dinner", FOOD, eur(3000), DAY, ana, ana, at);
        transactions.executeWithoutResult(status -> {
            events.publishEvent(event);
            events.publishEvent(event);
        });

        await().atMost(Duration.ofSeconds(10)).until(() -> items().size() == 1 && publicationsLeft() == 0);
        assertThat(items()).hasSize(1);
    }

    /** A member who joins, leaves and joins again has an item for each time. */
    @Test
    void joiningAgainIsAnotherItem(Scenario scenario) {
        Instant later = at.plusSeconds(60);
        scenario.publish(new MemberJoined(group, joao, at))
                .andWaitForStateChange(this::items, items -> !items.isEmpty())
                .andVerify(items -> assertThat(items).hasSize(1));
        scenario.publish(new MemberJoined(group, joao, later))
                .andWaitForStateChange(this::items, items -> items.size() == 2)
                .andVerify(items -> assertThat(items)
                        .extracting(Activity::occurredAt)
                        .as("newest first")
                        .containsExactly(later, at));
    }

    private List<Activity> items() {
        return store.page(group, null, 100).stream().map(Stored::activity).toList();
    }

    private long publicationsLeft() {
        return jdbc.sql("SELECT count(*) FROM event_publication")
                .query(Long.class)
                .single();
    }

    private static Money eur(long minor) {
        return new Money(minor, EUR);
    }

    private UUID newUser(String name) {
        return jdbc.sql("INSERT INTO users (email, name) VALUES (:email, :name) RETURNING id")
                .param("email", "activity-" + UUID.randomUUID() + "@example.com")
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    /** The rows the groups module would make: a group needs a ledger of its own. */
    private UUID newGroup(UUID createdBy) {
        UUID id = UUID.randomUUID();
        UUID ledger = jdbc.sql("INSERT INTO ledgers (owner_type, owner_id) VALUES ('GROUP', :id) RETURNING id")
                .param("id", id)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                        INSERT INTO groups (id, name, currency, ledger_id, created_by)
                        VALUES (:id, 'Porto weekend', 'EUR', :ledger, :createdBy)""")
                .param("id", id)
                .param("ledger", ledger)
                .param("createdBy", createdBy)
                .update();
        return id;
    }
}
