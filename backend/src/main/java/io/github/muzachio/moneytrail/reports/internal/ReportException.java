package io.github.muzachio.moneytrail.reports.internal;

import io.github.muzachio.moneytrail.shared.DomainException;
import org.springframework.http.HttpStatus;

/** The codes are listed in api/openapi.yaml (BadReportQuery). */
final class ReportException extends DomainException {

    private ReportException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    static ReportException invalidPeriod(String message) {
        return new ReportException(HttpStatus.BAD_REQUEST, "invalid_period", message);
    }
}
