package io.github.muzachio.moneytrail.identity.internal;

import io.github.muzachio.moneytrail.shared.DomainException;
import org.springframework.http.HttpStatus;

/** Signed in but not allowed, most often a state-changing request without a valid CSRF token. */
class ForbiddenException extends DomainException {

    ForbiddenException() {
        super(HttpStatus.FORBIDDEN, "forbidden", "This request is not allowed.");
    }
}
