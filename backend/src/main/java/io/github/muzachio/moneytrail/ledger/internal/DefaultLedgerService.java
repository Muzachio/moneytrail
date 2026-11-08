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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultLedgerService implements LedgerService {

    private final Ledgers ledgers;
    private final Accounts accounts;
    private final JournalEntries entries;
    private final AccountBalances balances;

    DefaultLedgerService(Ledgers ledgers, Accounts accounts, JournalEntries entries, AccountBalances balances) {
        this.ledgers = ledgers;
        this.accounts = accounts;
        this.entries = entries;
        this.balances = balances;
    }

    @Override
    public Ledger openLedger(LedgerOwner owner) {
        return ledgers.save(new LedgerEntity(owner)).toLedger();
    }

    @Override
    public Account openAccount(NewAccount account) {
        if (!ledgers.existsById(account.ledgerId())) {
            throw LedgerException.ledgerNotFound(account.ledgerId());
        }
        Account opened = accounts.saveAndFlush(new AccountEntity(account)).toAccount();
        balances.open(opened.id());
        return opened;
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

    private Map<UUID, Account> accountsOf(NewJournalEntry entry) {
        Set<UUID> ids = entry.postings().stream().map(Posting::accountId).collect(toSet());
        return accounts.findAllById(ids).stream()
                .map(AccountEntity::toAccount)
                .collect(toMap(Account::id, Function.identity()));
    }
}
