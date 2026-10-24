/**
 * Double-entry ledger: accounts, journal entries, postings and balances. The only module that
 * writes them; other modules go through {@code LedgerService}.
 */
@ApplicationModule(displayName = "Ledger")
@NullMarked
package io.github.muzachio.moneytrail.ledger;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
