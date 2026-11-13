package io.github.muzachio.moneytrail.ledger;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Where a page of {@link LedgerService#entries} ended: the last entry's day and id, the two values
 * the list is ordered by (keyset pagination).
 */
public record EntryPosition(LocalDate occurredOn, UUID entryId) {}
