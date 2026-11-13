package io.github.muzachio.moneytrail.personal;

import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the modules that show a user's personal ledger, such as {@code reports}, may read about it.
 * Nothing here writes: unlike the personal endpoints, it never opens a missing ledger, so a user who
 * has none yet (a session from before #42) simply has nothing in it.
 */
public interface PersonalLedgerQueries {

    /** The id of the user's personal ledger, if they have one. */
    Optional<UUID> ledgerOf(UUID userId);

    /** The currency of every amount in a personal ledger: the only one until multi-currency accounts (M4). */
    Currency currency();

    /**
     * The label of every category of a personal ledger, archived ones included, by id: the same
     * {@code key} and {@code name} that the categories endpoint sends.
     */
    Map<UUID, CategoryLabel> categoryLabels(UUID ledgerId);
}
