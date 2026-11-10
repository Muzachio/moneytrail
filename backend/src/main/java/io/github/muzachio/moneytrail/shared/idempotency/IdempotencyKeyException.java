package io.github.muzachio.moneytrail.shared.idempotency;

import io.github.muzachio.moneytrail.shared.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Why a request with an {@code Idempotency-Key} was refused. The 409 and 422 codes are the
 * {@code IdempotencyKeyInUse} and {@code IdempotencyKeyReused} responses of api/openapi.yaml.
 */
final class IdempotencyKeyException extends DomainException {

    private IdempotencyKeyException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    /** Same code as the 400 Spring MVC answers when the generated header parameter is missing. */
    static IdempotencyKeyException invalid() {
        return new IdempotencyKeyException(
                HttpStatus.BAD_REQUEST,
                "bad_request",
                "Send an " + IdempotentEndpoints.HEADER + " header of 1 to 255 characters.");
    }

    static IdempotencyKeyException inUse() {
        return new IdempotencyKeyException(
                HttpStatus.CONFLICT,
                "idempotency_key_in_use",
                "A request with this " + IdempotentEndpoints.HEADER
                        + " is still being processed. Retry when it has finished.");
    }

    static IdempotencyKeyException reused() {
        return new IdempotencyKeyException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "idempotency_key_reused",
                "This " + IdempotentEndpoints.HEADER + " was already used for a different request.");
    }
}
