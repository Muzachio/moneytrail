package io.github.muzachio.moneytrail.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A money movement to record in a ledger; see {@link LedgerService#post} for the rules. */
public record NewJournalEntry(
        UUID ledgerId, LocalDate occurredOn, String description, UUID createdBy, List<Posting> postings) {

    public NewJournalEntry {
        postings = List.copyOf(postings);
    }
}
