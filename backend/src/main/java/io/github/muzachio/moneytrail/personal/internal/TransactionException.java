package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.shared.DomainException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** The codes are listed in api/openapi.yaml (TransactionNotFound, ReversalConflict, TransactionRefused). */
final class TransactionException extends DomainException {

    private TransactionException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    /**
     * Also for another user's transaction, so that whether it exists is not revealed, and for the
     * entries that are not transactions: reversals and opening balances.
     */
    static TransactionException notFound(UUID transactionId) {
        return new TransactionException(
                HttpStatus.NOT_FOUND, "transaction_not_found", "There is no transaction " + transactionId + ".");
    }

    static TransactionException alreadyReversed(UUID transactionId) {
        return new TransactionException(
                HttpStatus.CONFLICT,
                "transaction_already_reversed",
                "Transaction " + transactionId + " was already reversed.");
    }

    /** Also for another user's account, a category and the opening balances account. */
    static TransactionException accountNotFound(UUID accountId) {
        return refused("account_not_found", "There is no account " + accountId + ".");
    }

    /** Also for another user's category and an account. */
    static TransactionException categoryNotFound(UUID categoryId) {
        return refused("category_not_found", "There is no category " + categoryId + ".");
    }

    static TransactionException categoryKindMismatch(UUID categoryId, UserTransaction.Type type) {
        return refused(
                "category_kind_mismatch",
                "Category " + categoryId + " is not an " + type.name().toLowerCase(Locale.ROOT) + " category.");
    }

    static TransactionException accountArchived(UUID accountId) {
        return refused("account_archived", "Account " + accountId + " is archived.");
    }

    static TransactionException categoryArchived(UUID categoryId) {
        return refused("category_archived", "Category " + categoryId + " is archived.");
    }

    // The code passed the schema's pattern (three capital letters), so it is safe in a message.
    static TransactionException currencyMismatch(String currencyCode) {
        return refused(
                "currency_mismatch", "The amount is in " + currencyCode + ", not in the currency of its accounts.");
    }

    // The same code as the schema rules' failures (api/openapi.yaml, BadRequest).
    static TransactionException badRequest(String message) {
        return new TransactionException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }

    private static TransactionException refused(String code, String message) {
        return new TransactionException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
