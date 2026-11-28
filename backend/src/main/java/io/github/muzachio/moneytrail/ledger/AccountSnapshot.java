package io.github.muzachio.moneytrail.ledger;

import io.github.muzachio.moneytrail.shared.Money;
import java.util.Set;
import java.util.UUID;

/**
 * An account's balance together with the entries it adds up, read at the same moment (see {@link
 * LedgerService#snapshot}).
 *
 * @param entryIds every entry with a posting to the account, reversals included
 */
public record AccountSnapshot(Money balance, Set<UUID> entryIds) {

    public AccountSnapshot {
        entryIds = Set.copyOf(entryIds);
    }
}
