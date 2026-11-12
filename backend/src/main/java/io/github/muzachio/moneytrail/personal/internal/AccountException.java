package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.shared.DomainException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class AccountException extends DomainException {

    private AccountException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    /**
     * Also for another user's account, and for a category or the opening balances account, so that
     * whether it exists is not revealed.
     */
    static AccountException notFound(UUID accountId) {
        return new AccountException(
                HttpStatus.NOT_FOUND, "account_not_found", "There is no account " + accountId + ".");
    }

    // The same code as the schema rules' failures (api/openapi.yaml, BadRequest).
    static AccountException badRequest(String message) {
        return new AccountException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }
}
