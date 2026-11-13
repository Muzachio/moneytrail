package io.github.muzachio.moneytrail.ledger;

import io.github.muzachio.moneytrail.shared.DomainException;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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

    /**
     * Cancels an entry by posting its exact opposite: every posting negated, on the same day and with
     * the same description, linked to it through {@code reversesEntryId}. Dated like the entry it
     * cancels, so that the sums of that day, and of its month, end up as if it had never been posted.
     * An entry is reversed at most once, and a reversal is never reversed itself.
     *
     * @param reversedBy the user who reverses it
     * @return the reversal
     * @throws DomainException {@code entry_not_found} (404), {@code entry_already_reversed} (409, also
     *     when another transaction reverses it at the same time), {@code entry_is_a_reversal} (422)
     */
    JournalEntry reverse(UUID entryId, UUID reversedBy);

    /**
     * Corrects an entry: reverses it as {@link #reverse} does and posts the replacement, linked to it
     * through {@code replacesEntryId}, both in the same transaction, so that neither exists without
     * the other. The replacement follows the rules of {@link #post} and is reversed by its {@code
     * createdBy}.
     *
     * @return the replacement
     * @throws DomainException those of {@link #reverse} and {@link #post}, and {@code
     *     replacement_in_another_ledger} (422)
     */
    JournalEntry replace(UUID entryId, NewJournalEntry replacement);

    Optional<JournalEntry> findEntry(UUID entryId);

    /**
     * Every version of an entry, oldest first: the first one, then, for each correction, the reversal
     * of the version before it and the replacement. It ends with the version in effect, or with the
     * reversal of the last version when that one was reversed without a replacement. The history of
     * any entry in it, a reversal included, is the same list. An entry that was never reversed is its
     * own history; an unknown one has none.
     */
    List<JournalEntry> history(UUID entryId);

    /**
     * A page of the entries that match the filter, newest first: by the day they occurred on, then by
     * id, which is time-ordered, so by when they were recorded. The page after another starts right
     * after its {@link EntryPage#next}, given as {@code after}; null asks for the first page.
     *
     * @param limit how many entries a page holds at most, at least 1
     */
    EntryPage entries(EntryFilter filter, @Nullable EntryPosition after, int limit);

    /**
     * How many entries match the filter, and the sums of their postings by kind of account: all the
     * matching entries, not one page. A ledger whose entries of one kind are in two currencies is not
     * supported yet (M4), and throws {@code IllegalStateException}.
     */
    EntryTotals totals(EntryFilter filter);

    /**
     * The account's balance: the sum of all its postings, in its currency, read from one row kept up
     * to date by {@link #post}.
     *
     * @throws DomainException {@code account_not_found} (404)
     */
    Money balance(UUID accountId);

    /** The balance of every account of a ledger, archived ones included, read as {@link #balance} is. */
    Map<UUID, Money> balances(UUID ledgerId);

    /**
     * How much each account of a ledger changed from one day to another, both included: the sum of
     * its postings in the entries that occurred on those days. An entry that posts to an {@link
     * AccountKind#EQUITY} account is left out, because it records money the owner already had, such
     * as an account's opening balance, not money that moved then. Accounts that did not change are
     * not in the map.
     */
    Map<UUID, Money> changes(UUID ledgerId, LocalDate from, LocalDate to);
}
