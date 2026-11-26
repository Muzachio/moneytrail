package io.github.muzachio.moneytrail.groups.internal;

import java.util.UUID;

/**
 * An item of a group's list: an {@link Expense}, or a {@link Settlement} from one member to another.
 * Each is one version, a journal entry of the group's ledger, and the group's list holds the
 * versions in effect of both.
 */
sealed interface Movement permits Expense, Settlement {

    /** The journal entry's id. */
    UUID id();
}
