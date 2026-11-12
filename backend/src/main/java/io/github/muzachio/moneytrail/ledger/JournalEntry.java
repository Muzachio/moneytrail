package io.github.muzachio.moneytrail.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A recorded money movement. It never changes: a mistake is corrected by a reversal, and optionally
 * a replacement (see {@link LedgerService#reverse} and {@link LedgerService#replace}).
 *
 * @param reversesEntryId the entry this one cancels, when it is a reversal
 * @param replacesEntryId the entry this one corrects, when it is a replacement
 * @param createdAt when the entry was recorded, which is not the day the money moved
 */
public record JournalEntry(
        UUID id,
        UUID ledgerId,
        LocalDate occurredOn,
        String description,
        UUID createdBy,
        Instant createdAt,
        @Nullable UUID reversesEntryId,
        @Nullable UUID replacesEntryId,
        List<Posting> postings) {

    public JournalEntry {
        postings = List.copyOf(postings);
    }

    public boolean isReversal() {
        return reversesEntryId != null;
    }
}
