package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.shared.DomainException;
import org.springframework.http.HttpStatus;

/** The mirror's own codes (api/openapi.yaml, MirrorRefused); the others are TransactionException's. */
final class MirrorException extends DomainException {

    private MirrorException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    static MirrorException currencyMismatch() {
        return new MirrorException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "mirror_currency_mismatch",
                "The group's currency is not the currency of your ledger.");
    }
}
