package io.github.muzachio.moneytrail.ledger;

import io.github.muzachio.moneytrail.shared.DomainException;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.Optional;
import java.util.UUID;

/**
 * The ledger module's public API, and the only way to write journal entries and postings. A broken
 * rule throws a {@link DomainException} whose {@code code} names the rule.
 */
public interface LedgerService {

    Ledger openLedger(LedgerOwner owner);

    /**
     * Opens an account in an existing ledger.
     *
     * @throws DomainException {@code ledger_not_found} (404)
     */
    Account openAccount(NewAccount account);

    /**
     * Records a journal entry and adds it to its accounts' balances, in the same transaction.
     * Entries are append-only: nothing ever updates or deletes them.
     *
     * @throws DomainException with one of these codes:
     *     <ul>
     *       <li>{@code too_few_postings}: fewer than 2 postings
     *       <li>{@code zero_amount}: a posting of zero
     *       <li>{@code unbalanced_entry}: the postings of some currency do not sum to zero
     *       <li>{@code amount_too_large}: the debits or credits of some currency add up to more
     *           than a {@code long} holds
     *       <li>{@code account_not_found} (404): a posting names an account that does not exist
     *       <li>{@code account_not_in_ledger}: a posting names an account of another ledger
     *       <li>{@code currency_mismatch}: a posting is not in its account's currency
     *     </ul>
     *     All of them are 422 unless stated.
     */
    JournalEntry post(NewJournalEntry entry);

    Optional<JournalEntry> findEntry(UUID entryId);

    /**
     * The account's balance: the sum of all its postings, in its currency, read from one row kept up
     * to date by {@link #post}.
     *
     * @throws DomainException {@code account_not_found} (404)
     */
    Money balance(UUID accountId);
}
