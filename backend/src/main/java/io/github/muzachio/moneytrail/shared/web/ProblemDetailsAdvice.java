package io.github.muzachio.moneytrail.shared.web;

import io.github.muzachio.moneytrail.shared.DomainException;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single place where errors become RFC 9457 problem details. Every problem carries a stable
 * {@code code}, including the ones Spring MVC raises itself (404, 405, ...), so clients never need
 * to parse messages.
 */
@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    @ExceptionHandler(DomainException.class)
    ProblemDetail handleDomainException(DomainException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setProperty("code", exception.code());
        return problem;
    }

    // The last hook before the response is written: Spring MVC only builds the ProblemDetail
    // body after handleExceptionInternal, so the code is added here.
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && !hasCode(problem)) {
            problem.setProperty("code", codeFor(statusCode));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static boolean hasCode(ProblemDetail problem) {
        Map<String, Object> properties = problem.getProperties();
        return properties != null && properties.containsKey("code");
    }

    private static String codeFor(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name().toLowerCase(Locale.ROOT) : "http_" + statusCode.value();
    }
}
