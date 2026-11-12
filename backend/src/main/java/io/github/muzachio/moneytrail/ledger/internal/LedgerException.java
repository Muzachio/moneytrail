package io.github.muzachio.moneytrail.ledger.internal;

import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.shared.DomainException;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** A ledger rule was broken. The codes are listed on {@code LedgerService}. */
final class LedgerException extends DomainException {

    private LedgerException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    static LedgerException ledgerNotFound(UUID ledgerId) {
        return new LedgerException(HttpStatus.NOT_FOUND, "ledger_not_found", "Ledger " + ledgerId + " does not exist.");
    }

    static LedgerException accountNotFound(UUID accountId) {
        return new LedgerException(
                HttpStatus.NOT_FOUND, "account_not_found", "Account " + accountId + " does not exist.");
    }

    static LedgerException entryNotFound(UUID entryId) {
        return new LedgerException(
                HttpStatus.NOT_FOUND, "entry_not_found", "Journal entry " + entryId + " does not exist.");
    }

    static LedgerException entryAlreadyReversed(UUID entryId) {
        return new LedgerException(
                HttpStatus.CONFLICT, "entry_already_reversed", "Journal entry " + entryId + " was already reversed.");
    }

    static LedgerException entryIsAReversal(UUID entryId) {
        return invalid(
                "entry_is_a_reversal",
                "Journal entry " + entryId + " is a reversal. Post the entry again instead of reversing the reversal.");
    }

    static LedgerException replacementInAnotherLedger(UUID entryId, UUID ledgerId) {
        return invalid(
                "replacement_in_another_ledger",
                "Journal entry " + entryId + " can only be replaced by an entry of its own ledger, not of " + ledgerId
                        + ".");
    }

    // The name is the user's own, but it stays out of the message: messages may end up in logs.
    static LedgerException categoryNameTaken(AccountKind kind) {
        return new LedgerException(
                HttpStatus.CONFLICT,
                "category_name_taken",
                "An active " + kind.name().toLowerCase(Locale.ROOT) + " category already has this name.");
    }

    static LedgerException tooFewPostings(int count) {
        return invalid("too_few_postings", "A journal entry needs at least 2 postings, not " + count + ".");
    }

    static LedgerException zeroAmount() {
        return invalid("zero_amount", "A posting cannot be zero.");
    }

    static LedgerException unbalancedEntry(Currency currency) {
        return invalid("unbalanced_entry", "The " + currency + " postings do not sum to zero.");
    }

    static LedgerException amountTooLarge(Currency currency) {
        return invalid("amount_too_large", "The " + currency + " postings add up to more than the ledger can hold.");
    }

    static LedgerException accountNotInLedger(UUID accountId, UUID ledgerId) {
        return invalid("account_not_in_ledger", "Account " + accountId + " is not in ledger " + ledgerId + ".");
    }

    static LedgerException currencyMismatch(UUID accountId, Currency accountCurrency, Currency postingCurrency) {
        return invalid(
                "currency_mismatch",
                "Account " + accountId + " is in " + accountCurrency + ", not " + postingCurrency + ".");
    }

    private static LedgerException invalid(String code, String message) {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
