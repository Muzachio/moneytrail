package io.github.muzachio.moneytrail.ledger;

import io.github.muzachio.moneytrail.shared.DomainException;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ledger module's public API, and the only way to write journal entries and postings. A broken
 * rule throws a {@link DomainException} whose {@code code} names the rule.
 *
 * <p>An owner has one ledger at most. The active categories ({@link AccountKind#EXPENSE} and {@link
 * AccountKind#INCOME} accounts) of a ledger have different names within each kind, ignoring case;
 * opening, renaming or restoring an account that would break this throws {@code
 * category_name_taken} (409).
 */
public interface LedgerService {

    /** Opens the ledger of an owner that has none; for one that may have one, see below. */
    Ledger openLedger(LedgerOwner owner);

    /**
     * Opens the owner's ledger unless it already has one. If another transaction is opening it at
     * the same moment, this one waits for it instead of failing: it then opens nothing if that
     * transaction commits, and opens the ledger if that one rolls back.
     *
     * @return the new ledger, or empty when the owner already had one
     */
    Optional<Ledger> openLedgerIfAbsent(LedgerOwner owner);

    Optional<Ledger> findLedger(LedgerOwner owner);

    /**
     * Opens an account in an existing ledger.
     *
     * @throws DomainException {@code ledger_not_found} (404), {@code category_name_taken} (409)
     */
    Account openAccount(NewAccount account);

    /** All the accounts of a ledger, archived ones included, by id, so in the order they were opened. */
    List<Account> accounts(UUID ledgerId);

    Optional<Account> findAccount(UUID accountId);

    /**
     * Gives the account a new name.
     *
     * @throws DomainException {@code account_not_found} (404), {@code category_name_taken} (409)
     */
    Account renameAccount(UUID accountId, String name);

    /**
     * Archives the account; it keeps its postings and balance. Archiving it again changes nothing.
     *
     * @throws DomainException {@code account_not_found} (404)
     */
    Account archiveAccount(UUID accountId);

    /**
     * Brings an archived account back. Restoring an active one changes nothing.
     *
     * @throws DomainException {@code account_not_found} (404), {@code category_name_taken} (409)
     */
    Account restoreAccount(UUID accountId);

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
