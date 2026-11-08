package io.github.muzachio.moneytrail.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A recorded money movement. It never changes: a mistake is corrected by a reversal. */
public record JournalEntry(
        UUID id, UUID ledgerId, LocalDate occurredOn, String description, UUID createdBy, List<Posting> postings) {

    public JournalEntry {
        postings = List.copyOf(postings);
    }
}
