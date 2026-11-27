package io.github.muzachio.moneytrail.activity.internal;

import io.github.muzachio.moneytrail.shared.DomainException;
import org.springframework.http.HttpStatus;

/** The code is listed in api/openapi.yaml (BadRequest). */
final class ActivityException extends DomainException {

    private ActivityException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    // The same code as the schema rules' failures (api/openapi.yaml, BadRequest).
    static ActivityException badRequest(String message) {
        return new ActivityException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }
}
