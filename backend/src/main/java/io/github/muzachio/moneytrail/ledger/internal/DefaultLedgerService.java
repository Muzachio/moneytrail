package io.github.muzachio.moneytrail.ledger.internal;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.Ledger;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultLedgerService implements LedgerService {

    // V8__one_ledger_per_owner_and_category_names.sql
    private static final String CATEGORY_NAME_CONSTRAINT = "accounts_category_name_key";

    private final Ledgers ledgers;
    private final LedgerInserts ledgerInserts;
    private final Accounts accounts;
    private final JournalEntries entries;
    private final AccountBalances balances;

    DefaultLedgerService(
            Ledgers ledgers,
            LedgerInserts ledgerInserts,
            Accounts accounts,
            JournalEntries entries,
            AccountBalances balances) {
        this.ledgers = ledgers;
        this.ledgerInserts = ledgerInserts;
        this.accounts = accounts;
        this.entries = entries;
        this.balances = balances;
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
        account.archive(Instant.now());
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
        PostingRules.checkAmounts(entry.postings());
        PostingRules.checkAccounts(entry, accountsOf(entry));
        balances.apply(entry.postings());
        return entries.save(new JournalEntryEntity(entry)).toJournalEntry();
    }

    @Override
    @Transactional(readOnly = true)
    public Money balance(UUID accountId) {
        return balances.find(accountId).orElseThrow(() -> LedgerException.accountNotFound(accountId));
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
