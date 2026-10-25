package io.github.muzachio.moneytrail.identity.internal;

import io.github.muzachio.moneytrail.shared.DomainException;
import org.springframework.http.HttpStatus;

class UnauthenticatedException extends DomainException {

    UnauthenticatedException() {
        super(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to use this endpoint.");
    }
}
