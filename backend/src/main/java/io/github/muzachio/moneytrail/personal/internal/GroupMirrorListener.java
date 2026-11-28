package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.groups.GroupExpenseAdded;
import io.github.muzachio.moneytrail.groups.GroupExpenseEdited;
import io.github.muzachio.moneytrail.groups.GroupExpenseReversed;
import io.github.muzachio.moneytrail.groups.MemberLeft;
import io.github.muzachio.moneytrail.groups.SettlementEdited;
import io.github.muzachio.moneytrail.groups.SettlementRecorded;
import io.github.muzachio.moneytrail.groups.SettlementReversed;
import io.github.muzachio.moneytrail.personal.internal.GroupMirrorStore.EntryType;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Records each member's share of a group's expenses and payments in their personal ledger when their
 * mirror of the group is on (ADR-0015). Through the outbox, like the activity feed (ADR-0014): after the
 * group's change commits, in a thread and a transaction of its own, so one member's expense never fails
 * on another member's ledger, and a failure is resubmitted within the hour.
 *
 * <p>Delivery is at least once and, after a failure, out of order; {@link GroupMirrors} keeps a row per
 * group entry and member, so nothing is recorded twice. The methods are public for Spring's proxies.
 */
@Component
class GroupMirrorListener {

    private final GroupMirrors mirrors;

    GroupMirrorListener(GroupMirrors mirrors) {
        this.mirrors = mirrors;
    }

    @ApplicationModuleListener
    public void on(GroupExpenseAdded event) {
        mirrors.recorded(event.groupId(), event.expenseId(), EntryType.EXPENSE, event.category(), event.description());
    }

    @ApplicationModuleListener
    public void on(GroupExpenseEdited event) {
        mirrors.corrected(
                event.groupId(),
                event.replacesExpenseId(),
                event.expenseId(),
                EntryType.EXPENSE,
                event.category(),
                event.description());
    }

    @ApplicationModuleListener
    public void on(GroupExpenseReversed event) {
        mirrors.reversed(event.groupId(), event.expenseId(), EntryType.EXPENSE);
    }

    @ApplicationModuleListener
    public void on(SettlementRecorded event) {
        mirrors.recorded(event.groupId(), event.settlementId(), EntryType.SETTLEMENT, null, "");
    }

    @ApplicationModuleListener
    public void on(SettlementEdited event) {
        mirrors.corrected(
                event.groupId(), event.replacesSettlementId(), event.settlementId(), EntryType.SETTLEMENT, null, "");
    }

    @ApplicationModuleListener
    public void on(SettlementReversed event) {
        mirrors.reversed(event.groupId(), event.settlementId(), EntryType.SETTLEMENT);
    }

    @ApplicationModuleListener
    public void on(MemberLeft event) {
        mirrors.left(event.groupId(), event.userId());
    }
}
