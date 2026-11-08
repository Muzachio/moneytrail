package io.github.muzachio.moneytrail.ledger.internal;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What a journal entry must satisfy before it is posted (ADR-0004). */
final class PostingRules {

    private PostingRules() {}

    /** The rules that need only the postings, so a bad entry is refused before any database read. */
    static void checkAmounts(List<Posting> postings) {
        if (postings.size() < 2) {
            throw LedgerException.tooFewPostings(postings.size());
        }
        if (postings.stream().anyMatch(posting -> posting.amount().isZero())) {
            throw LedgerException.zeroAmount();
        }
        checkBalanced(postings);
    }

    static void checkAccounts(NewJournalEntry entry, Map<UUID, Account> accountsById) {
        for (Posting posting : entry.postings()) {
            Account account = accountsById.get(posting.accountId());
            if (account == null) {
                throw LedgerException.accountNotFound(posting.accountId());
            }
            if (!account.ledgerId().equals(entry.ledgerId())) {
                throw LedgerException.accountNotInLedger(account.id(), entry.ledgerId());
            }
            Currency postingCurrency = posting.amount().currency();
            if (!account.currency().equals(postingCurrency)) {
                throw LedgerException.currencyMismatch(account.id(), account.currency(), postingCurrency);
            }
        }
    }

    // Debits and credits are added up apart: then whether a sum overflows a long does not depend on
    // the order of the postings ([MAX, 1, -MAX, -1] and [MAX, -MAX, 1, -1] get the same answer), and
    // their final sum cannot overflow, as one is never negative and the other never positive.
    private static void checkBalanced(List<Posting> postings) {
        Map<Currency, Money> debits = new HashMap<>();
        Map<Currency, Money> credits = new HashMap<>();
        for (Posting posting : postings) {
            Money amount = posting.amount();
            Map<Currency, Money> side = amount.minor() > 0 ? debits : credits;
            try {
                side.merge(amount.currency(), amount, Money::plus);
            } catch (ArithmeticException overflow) {
                throw LedgerException.amountTooLarge(amount.currency());
            }
        }
        Map<Currency, Money> totals = new HashMap<>(debits);
        credits.forEach((currency, credit) -> totals.merge(currency, credit, Money::plus));
        totals.forEach((currency, total) -> {
            if (!total.isZero()) {
                throw LedgerException.unbalancedEntry(currency);
            }
        });
    }
}
