package io.github.muzachio.moneytrail.ledger;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A page of {@link LedgerService#entries}.
 *
 * @param next where the next page starts, or null on the last page
 */
public record EntryPage(
        List<JournalEntry> entries, @Nullable EntryPosition next) {

    public EntryPage {
        entries = List.copyOf(entries);
    }
}
