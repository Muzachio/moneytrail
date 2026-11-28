package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.personal.internal.GroupMirrorStore.EntryType;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What one entry of a group's ledger was for one member, read from their postings in it, and the
 * postings that record it in their personal ledger (ADR-0015, "Posting rules").
 *
 * @param paid the sum of their positive postings: what they paid for an expense, or paid another member
 * @param share the sum of their negative postings, as a positive amount: their share of an expense, or
 *     what another member paid them
 */
record GroupShare(Money paid, Money share) {

    /**
     * ADR-0012 keeps a payer's {@code +T} and {@code −share} as two postings, so both can be read back
     * here; were they netted, a payer's share would be lost.
     */
    static GroupShare of(JournalEntry entry, UUID memberAccountId, Money zero) {
        Money paid = zero;
        Money share = zero;
        for (Posting posting : entry.postings()) {
            if (posting.accountId().equals(memberAccountId)) {
                if (posting.amount().minor() > 0) {
                    paid = paid.plus(posting.amount());
                } else {
                    share = share.minus(posting.amount());
                }
            }
        }
        return new GroupShare(paid, share);
    }

    /** False for a member with no posting in the entry: they get no personal entry. */
    boolean involved() {
        return !paid.isZero() || !share.isZero();
    }

    /** How much the member's account in the group moved, and so how much the group's account must. */
    Money change() {
        return paid.minus(share);
    }

    /**
     * The personal entry's postings: debits first, and no posting of zero. In every case the group's
     * account moves by {@link #change()}, which keeps its balance equal to the member's balance in the
     * group; and they always sum to zero.
     *
     * <ul>
     *   <li>An expense: {@code +share} to the category, {@code −paid} from the paying account, the rest
     *       to the group's account (what the others owe them, or what they owe).
     *   <li>A settlement: {@code −paid} from, or {@code +received} into, the paying account, against the
     *       group's account: a transfer, never spending.
     * </ul>
     */
    List<Posting> postings(EntryType type, UUID categoryId, UUID paymentAccountId, UUID groupAccountId) {
        List<Posting> postings = new ArrayList<>();
        if (type == EntryType.EXPENSE) {
            add(postings, categoryId, share);
            add(postings, paymentAccountId, paid.negate());
        } else {
            add(postings, paymentAccountId, share.minus(paid));
        }
        add(postings, groupAccountId, change());
        postings.sort((a, b) -> Long.compare(b.amount().minor(), a.amount().minor()));
        return postings;
    }

    private static void add(List<Posting> postings, UUID accountId, Money amount) {
        if (!amount.isZero()) {
            postings.add(new Posting(accountId, amount));
        }
    }
}
