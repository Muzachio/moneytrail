package io.github.muzachio.moneytrail.ledger.internal;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.EntryFilter;
import io.github.muzachio.moneytrail.ledger.EntryPage;
import io.github.muzachio.moneytrail.ledger.EntryPosition;
import io.github.muzachio.moneytrail.ledger.EntryTotals;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.Ledger;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultLedgerService implements LedgerService {

    // V8__one_ledger_per_owner_and_category_names.sql
    private static final String CATEGORY_NAME_CONSTRAINT = "accounts_category_name_key";

    // V4__ledger.sql: journal_entries.reverses_entry_id is UNIQUE.
    private static final String REVERSED_ONCE_CONSTRAINT = "journal_entries_reverses_entry_id_key";

    private final Ledgers ledgers;
    private final LedgerInserts ledgerInserts;
    private final Accounts accounts;
    private final JournalEntries entries;
    private final AccountBalances balances;
    private final AccountChanges changes;
    private final EntryQueries entryQueries;

    DefaultLedgerService(
            Ledgers ledgers,
            LedgerInserts ledgerInserts,
            Accounts accounts,
            JournalEntries entries,
            AccountBalances balances,
            AccountChanges changes,
            EntryQueries entryQueries) {
        this.ledgers = ledgers;
        this.ledgerInserts = ledgerInserts;
        this.accounts = accounts;
        this.entries = entries;
        this.balances = balances;
        this.changes = changes;
        this.entryQueries = entryQueries;
    }

    @Override
    public Ledger openLedger(LedgerOwner owner) {
        return ledgers.save(new LedgerEntity(owner)).toLedger();
    }

    @Override
    public Optional<Ledger> openLedgerIfAbsent(LedgerOwner owner) {
        return ledgerInserts.insertIfAbsent(owner).map(id -> new Ledger(id, owner));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Ledger> findLedger(LedgerOwner owner) {
        return ledgers.findByOwnerTypeAndOwnerId(owner.type(), owner.id()).map(LedgerEntity::toLedger);
    }

    @Override
    public Account openAccount(NewAccount account) {
        if (!ledgers.existsById(account.ledgerId())) {
            throw LedgerException.ledgerNotFound(account.ledgerId());
        }
        Account opened = save(new AccountEntity(account));
        balances.open(opened.id());
        return opened;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Account> accounts(UUID ledgerId) {
        return accounts.findByLedgerIdOrderById(ledgerId).stream()
                .map(AccountEntity::toAccount)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> findAccount(UUID accountId) {
        return accounts.findById(accountId).map(AccountEntity::toAccount);
    }

    @Override
    public Account renameAccount(UUID accountId, String name) {
        AccountEntity account = existingAccount(accountId);
        account.rename(name);
        return save(account);
    }

    @Override
    public Account archiveAccount(UUID accountId) {
        AccountEntity account = existingAccount(accountId);
        // Postgres keeps microseconds: the date returned now is the one read back later.
        account.archive(Instant.now().truncatedTo(ChronoUnit.MICROS));
        return save(account);
    }

    @Override
    public Account restoreAccount(UUID accountId) {
        AccountEntity account = existingAccount(accountId);
        account.restore();
        return save(account);
    }

    @Override
    public JournalEntry post(NewJournalEntry entry) {
        return record(entry, new JournalEntryEntity(entry));
    }

    @Override
    public JournalEntry reverse(UUID entryId, UUID reversedBy) {
        return postReversal(reversible(entryId), reversedBy);
    }

    @Override
    public JournalEntry replace(UUID entryId, NewJournalEntry replacement) {
        JournalEntry entry = reversible(entryId);
        if (!entry.ledgerId().equals(replacement.ledgerId())) {
            throw LedgerException.replacementInAnotherLedger(entryId, replacement.ledgerId());
        }
        // Before the reversal writes anything, though the exception would roll it back anyway.
        PostingRules.checkAmounts(replacement.postings());
        postReversal(entry, replacement.createdBy());
        return record(replacement, JournalEntryEntity.replacement(replacement, entryId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<JournalEntry> history(UUID entryId) {
        Optional<JournalEntry> found = entries.findById(entryId).map(JournalEntryEntity::toJournalEntry);
        if (found.isEmpty()) {
            return List.of();
        }
        JournalEntry version = found.get();
        UUID reversed = version.reversesEntryId();
        if (reversed != null) {
            version = existingEntry(reversed);
        }
        for (UUID replaced = version.replacesEntryId(); replaced != null; replaced = version.replacesEntryId()) {
            version = existingEntry(replaced);
        }
        List<JournalEntry> history = new ArrayList<>(List.of(version));
        while (true) {
            Optional<JournalEntry> reversal =
                    entries.findByReversesEntryId(version.id()).map(JournalEntryEntity::toJournalEntry);
            if (reversal.isEmpty()) {
                break;
            }
            history.add(reversal.get());
            Optional<JournalEntry> replacement =
                    entries.findByReplacesEntryId(version.id()).map(JournalEntryEntity::toJournalEntry);
            if (replacement.isEmpty()) {
                break;
            }
            history.add(replacement.get());
            version = replacement.get();
        }
        return List.copyOf(history);
    }

    @Override
    @Transactional(readOnly = true)
    public EntryPage entries(EntryFilter filter, @Nullable EntryPosition after, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("A page holds at least 1 entry, not " + limit);
        }
        return entryQueries.page(filter, after, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public EntryTotals totals(EntryFilter filter) {
        return entryQueries.totals(filter);
    }

    private JournalEntry record(NewJournalEntry entry, JournalEntryEntity entity) {
        PostingRules.checkAmounts(entry.postings());
        PostingRules.checkAccounts(entry, accountsOf(entry));
        balances.apply(entry.postings());
        return entries.saveAndFlush(entity).toJournalEntry();
    }

    private JournalEntry reversible(UUID entryId) {
        JournalEntry entry = existingEntry(entryId);
        if (entry.isReversal()) {
            throw LedgerException.entryIsAReversal(entryId);
        }
        if (entries.findByReversesEntryId(entryId).isPresent()) {
            throw LedgerException.entryAlreadyReversed(entryId);
        }
        return entry;
    }

    /**
     * The check in {@link #reversible} cannot see a reversal that another transaction has not
     * committed yet. That transaction holds the locks on the accounts' balances, so this one waits for
     * it in {@code balances.apply} and then meets its reversal in the unique index, which answers the
     * same as the check. The failed statement aborts the transaction, which the exception rolls back.
     */
    private JournalEntry postReversal(JournalEntry entry, UUID reversedBy) {
        NewJournalEntry reversal = new NewJournalEntry(
                entry.ledgerId(),
                entry.occurredOn(),
                entry.description(),
                reversedBy,
                entry.postings().stream()
                        .map(posting -> new Posting(
                                posting.accountId(), posting.amount().negate()))
                        .toList());
        try {
            return record(reversal, JournalEntryEntity.reversal(reversal, entry.id()));
        } catch (DataIntegrityViolationException e) {
            if (violates(e, REVERSED_ONCE_CONSTRAINT)) {
                throw LedgerException.entryAlreadyReversed(entry.id());
            }
            throw e;
        }
    }

    private JournalEntry existingEntry(UUID entryId) {
        return entries.findById(entryId)
                .map(JournalEntryEntity::toJournalEntry)
                .orElseThrow(() -> LedgerException.entryNotFound(entryId));
    }

    @Override
    @Transactional(readOnly = true)
    public Money balance(UUID accountId) {
        return balances.find(accountId).orElseThrow(() -> LedgerException.accountNotFound(accountId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Money> balances(UUID ledgerId) {
        return balances.findByLedger(ledgerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Money> changes(UUID ledgerId, LocalDate from, LocalDate to) {
        return changes.between(ledgerId, from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JournalEntry> findEntry(UUID entryId) {
        return entries.findById(entryId).map(JournalEntryEntity::toJournalEntry);
    }

    private AccountEntity existingAccount(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> LedgerException.accountNotFound(accountId));
    }

    /**
     * The database decides whether a category's name is taken: a check in Java first would race with
     * a concurrent request, and Postgres folds case the same way for both (the index's lower()). The
     * failed statement aborts the transaction, which the exception rolls back anyway.
     */
    private Account save(AccountEntity account) {
        try {
            return accounts.saveAndFlush(account).toAccount();
        } catch (DataIntegrityViolationException e) {
            if (violates(e, CATEGORY_NAME_CONSTRAINT)) {
                throw LedgerException.categoryNameTaken(account.kind());
            }
            throw e;
        }
    }

    private static boolean violates(Throwable failure, String constraint) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraint.equals(violation.getConstraintName());
            }
        }
        return false;
    }

    private Map<UUID, Account> accountsOf(NewJournalEntry entry) {
        Set<UUID> ids = entry.postings().stream().map(Posting::accountId).collect(toSet());
        return accounts.findAllById(ids).stream()
                .map(AccountEntity::toAccount)
                .collect(toMap(Account::id, Function.identity()));
    }
}
