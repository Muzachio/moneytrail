package io.github.muzachio.moneytrail.ledger;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Which entries of a ledger {@link LedgerService#entries} lists and {@link LedgerService#totals} adds
 * up. Only entries in effect count: never a reversal, nor an entry that was reversed, so of a
 * corrected entry only its latest version. Every condition must hold; an empty set or a null adds
 * none.
 *
 * @param from and {@code to}: the days the entries occurred on, both included
 * @param postingTo accounts the entry posts to, every one of them
 * @param postingToKind a kind of account the entry posts to, at least once
 * @param notPostingToKinds kinds of account the entry never posts to
 * @param descriptionContains text the description contains, ignoring case
 */
public record EntryFilter(
        UUID ledgerId,
        LocalDate from,
        LocalDate to,
        Set<UUID> postingTo,
        @Nullable AccountKind postingToKind,
        Set<AccountKind> notPostingToKinds,
        @Nullable String descriptionContains) {

    public EntryFilter {
        postingTo = Set.copyOf(postingTo);
        notPostingToKinds = Set.copyOf(notPostingToKinds);
    }
}
