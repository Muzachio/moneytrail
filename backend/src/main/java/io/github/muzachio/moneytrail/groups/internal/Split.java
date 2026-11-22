package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.shared.Money;
import java.util.List;
import java.util.UUID;

/**
 * An expense divided among the people in it. The shares add up to the total and come in the order
 * of the members' ids as text, whatever the order they were given in.
 */
record Split(SplitMethod method, Money total, List<MemberShare> shares) {

    Split {
        shares = List.copyOf(shares);
    }

    /**
     * One person's part: what they were given and what they pay. A share can be zero (two cents
     * split three ways), which the expense records but posts nothing for, since the ledger refuses
     * zero amounts.
     */
    record MemberShare(UUID memberId, long input, Money share) {}
}
