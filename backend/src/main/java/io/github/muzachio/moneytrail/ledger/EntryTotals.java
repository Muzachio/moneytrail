package io.github.muzachio.moneytrail.ledger;

import io.github.muzachio.moneytrail.shared.Money;
import java.util.Map;

/**
 * What {@link LedgerService#totals} adds up.
 *
 * @param count how many entries match
 * @param sums the sum of their postings to each kind of account, signed as postings are; a kind they
 *     do not post to is absent
 */
public record EntryTotals(long count, Map<AccountKind, Money> sums) {

    public EntryTotals {
        sums = Map.copyOf(sums);
    }
}
