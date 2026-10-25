package io.github.muzachio.moneytrail.shared;

import org.springframework.http.HttpStatus;

/**
 * Base for exceptions that reach API clients. The {@code code} is part of the API contract:
 * clients branch on it, while the message may change or be translated.
 */
public abstract class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected DomainException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
