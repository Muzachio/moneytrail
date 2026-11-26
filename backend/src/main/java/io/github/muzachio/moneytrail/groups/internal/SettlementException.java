package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.shared.DomainException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The codes are listed in api/openapi.yaml (SettlementNotFound, SettlementConflict, SettlementRefused,
 * BadRequest). {@code member_not_found} and {@code currency_mismatch} are the same as for expenses.
 */
final class SettlementException extends DomainException {

    private SettlementException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    /**
     * Also for a settlement of another group, even one of the user's, and for an entry of the group's
     * ledger that is not a settlement, such as an expense or a reversal: whether it exists elsewhere
     * is not revealed.
     */
    static SettlementException notFound(UUID settlementId) {
        return new SettlementException(
                HttpStatus.NOT_FOUND,
                "group_settlement_not_found",
                "There is no settlement " + settlementId + " in the group.");
    }

    static SettlementException alreadyReversed(UUID settlementId) {
        return new SettlementException(
                HttpStatus.CONFLICT,
                "group_settlement_already_reversed",
                "Settlement " + settlementId + " was already reversed.");
    }

    /** ADR-0012: reversing it would change the balance of someone who is not in the group any more. */
    static SettlementException memberLeft(UUID settlementId) {
        return new SettlementException(
                HttpStatus.CONFLICT,
                "group_settlement_member_left",
                "Settlement " + settlementId + " is from or to someone who has left the group.");
    }

    static SettlementException toSelf() {
        return new SettlementException(
                HttpStatus.UNPROCESSABLE_CONTENT, "group_settlement_to_self", "A member cannot pay themselves.");
    }

    /** Also for someone who left the group, or who was never in it, so that who is in it is not revealed. */
    static SettlementException memberNotFound(UUID userId) {
        return new SettlementException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "member_not_found",
                "There is no member " + userId + " in the group.");
    }

    // The code passed the schema's pattern (three capital letters), so it is safe in a message.
    static SettlementException currencyMismatch(String currencyCode) {
        return new SettlementException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "currency_mismatch",
                "The amount is in " + currencyCode + ", not in the group's currency.");
    }

    // The same code as the schema rules' failures (api/openapi.yaml, BadRequest).
    static SettlementException badRequest(String message) {
        return new SettlementException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }
}
