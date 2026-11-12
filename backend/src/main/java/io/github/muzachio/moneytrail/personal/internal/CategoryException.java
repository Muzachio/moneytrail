package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.shared.DomainException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

final class CategoryException extends DomainException {

    private CategoryException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    /** Also for another user's category, so that its existence is not revealed. */
    static CategoryException notFound(UUID categoryId) {
        return new CategoryException(
                HttpStatus.NOT_FOUND, "category_not_found", "There is no category " + categoryId + ".");
    }

    // The same code as the schema rules' failures (api/openapi.yaml, BadRequest).
    static CategoryException badRequest(String message) {
        return new CategoryException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }
}
