package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.groups.internal.GroupStore.Lock;
import io.github.muzachio.moneytrail.groups.internal.GroupStore.Member;
import io.github.muzachio.moneytrail.groups.internal.GroupStore.Row;
import io.github.muzachio.moneytrail.groups.internal.SettleUpSuggestions.Suggestion;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who owes whom in a group, straight from {@code account_balances} (ADR-0012: a group's balances need
 * no query of their own), and the payments that would settle it up.
 */
@Service
@Transactional(readOnly = true)
class Balances {

    /** What the group owes a member: positive, the others owe them; negative, they owe the others. */
    record MemberBalance(UUID userId, Money balance) {}

    private final GroupStore groupStore;
    private final LedgerService ledger;

    Balances(GroupStore groupStore, LedgerService ledger) {
        this.groupStore = groupStore;
        this.ledger = ledger;
    }

    /**
     * Every current member's balance, in the order they joined. Someone who left is not in it: they
     * could only leave with a balance of zero, so the balances listed still add up to zero.
     */
    List<MemberBalance> of(UUID userId, UUID groupId) {
        Row group = groupStore
                .findForMember(groupId, userId, Lock.NONE)
                .orElseThrow(() -> GroupException.notFound(groupId));
        List<Member> members = groupStore.members(group.id());
        Map<UUID, Money> balances =
                ledger.balancesOf(members.stream().map(Member::accountId).toList());
        return members.stream()
                .map(member -> new MemberBalance(
                        member.userId(),
                        Objects.requireNonNull(
                                balances.get(member.accountId()),
                                () -> "Member account " + member.accountId() + " has no balance")))
                .toList();
    }

    /** Worked out from the balances on every call, never stored (ADR-0012). */
    List<Suggestion> settleUp(UUID userId, UUID groupId) {
        return SettleUpSuggestions.suggest(of(userId, groupId));
    }
}
