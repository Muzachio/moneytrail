package io.github.muzachio.moneytrail.activity.internal;

import io.github.muzachio.moneytrail.groups.GroupCreated;
import io.github.muzachio.moneytrail.groups.GroupExpenseAdded;
import io.github.muzachio.moneytrail.groups.GroupExpenseEdited;
import io.github.muzachio.moneytrail.groups.GroupExpenseReversed;
import io.github.muzachio.moneytrail.groups.MemberJoined;
import io.github.muzachio.moneytrail.groups.MemberLeft;
import io.github.muzachio.moneytrail.groups.SettlementEdited;
import io.github.muzachio.moneytrail.groups.SettlementRecorded;
import io.github.muzachio.moneytrail.groups.SettlementReversed;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns each event of the groups module into an item of the group's feed (ADR-0014). Each method is an
 * {@code @ApplicationModuleListener}: Spring Modulith stores the event in {@code event_publication} in
 * the transaction that publishes it, and calls the method after that transaction commits, in another
 * thread and a transaction of its own. A method that fails leaves its publication incomplete, for the
 * hourly resubmission of shared.events, and never fails the request that published the event.
 *
 * <p>Delivery is at least once, so {@link ActivityStore#add} adds nothing for an event it has seen. The
 * methods are public because Spring's proxies, which add the thread and the transaction, intercept
 * only public methods reliably.
 */
@Component
class ActivityListener {

    private final ActivityStore store;

    ActivityListener(ActivityStore store) {
        this.store = store;
    }

    @ApplicationModuleListener
    public void on(GroupCreated event) {
        store.add(Activity.of(
                        event.groupId(),
                        ActivityKind.GROUP_CREATED,
                        event.createdBy(),
                        event.createdAt(),
                        event.groupId())
                .withGroupName(event.name()));
    }

    @ApplicationModuleListener
    public void on(MemberJoined event) {
        store.add(Activity.of(
                event.groupId(), ActivityKind.MEMBER_JOINED, event.userId(), event.joinedAt(), event.userId()));
    }

    @ApplicationModuleListener
    public void on(MemberLeft event) {
        store.add(
                Activity.of(event.groupId(), ActivityKind.MEMBER_LEFT, event.userId(), event.leftAt(), event.userId()));
    }

    @ApplicationModuleListener
    public void on(GroupExpenseAdded event) {
        store.add(Activity.of(
                        event.groupId(),
                        ActivityKind.EXPENSE_ADDED,
                        event.recordedBy(),
                        event.recordedAt(),
                        event.expenseId())
                .withExpense(
                        event.description(), event.category(), event.amount(), event.paidBy(), event.occurredOn()));
    }

    @ApplicationModuleListener
    public void on(GroupExpenseEdited event) {
        store.add(Activity.of(
                        event.groupId(),
                        ActivityKind.EXPENSE_EDITED,
                        event.editedBy(),
                        event.editedAt(),
                        event.expenseId())
                .withExpense(event.description(), event.category(), event.amount(), event.paidBy(), event.occurredOn())
                .withPrevious(event.previousDescription(), event.previousCategory(), event.previousAmount()));
    }

    @ApplicationModuleListener
    public void on(GroupExpenseReversed event) {
        store.add(Activity.of(
                        event.groupId(),
                        ActivityKind.EXPENSE_REVERSED,
                        event.reversedBy(),
                        event.reversedAt(),
                        event.expenseId())
                .withExpense(event.description(), event.category(), event.amount(), null, null));
    }

    @ApplicationModuleListener
    public void on(SettlementRecorded event) {
        store.add(Activity.of(
                        event.groupId(),
                        ActivityKind.SETTLEMENT_RECORDED,
                        event.recordedBy(),
                        event.recordedAt(),
                        event.settlementId())
                .withSettlement(event.amount(), event.from(), event.to(), event.occurredOn()));
    }

    @ApplicationModuleListener
    public void on(SettlementEdited event) {
        store.add(Activity.of(
                        event.groupId(),
                        ActivityKind.SETTLEMENT_EDITED,
                        event.editedBy(),
                        event.editedAt(),
                        event.settlementId())
                .withSettlement(event.amount(), event.from(), event.to(), event.occurredOn())
                .withPrevious(null, null, event.previousAmount()));
    }

    @ApplicationModuleListener
    public void on(SettlementReversed event) {
        store.add(Activity.of(
                        event.groupId(),
                        ActivityKind.SETTLEMENT_REVERSED,
                        event.reversedBy(),
                        event.reversedAt(),
                        event.settlementId())
                .withSettlement(event.amount(), event.from(), event.to(), null));
    }
}
