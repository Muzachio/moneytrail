package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.shared.DomainException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The codes are listed in api/openapi.yaml (GroupExpenseNotFound, GroupExpenseConflict,
 * GroupExpenseRefused, BadRequest). A split that cannot be made throws {@link SplitException}.
 */
final class GroupExpenseException extends DomainException {

    private GroupExpenseException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    /**
     * Also for an expense of another group, even one of the user's, and for an entry of the group's
     * ledger that is not an expense, such as a reversal: whether it exists elsewhere is not revealed.
     */
    static GroupExpenseException notFound(UUID expenseId) {
        return new GroupExpenseException(
                HttpStatus.NOT_FOUND, "group_expense_not_found", "There is no expense " + expenseId + " in the group.");
    }

    static GroupExpenseException alreadyReversed(UUID expenseId) {
        return new GroupExpenseException(
                HttpStatus.CONFLICT,
                "group_expense_already_reversed",
                "Expense " + expenseId + " was already reversed.");
    }

    /** ADR-0012: reversing it would change the balance of someone who is not in the group any more. */
    static GroupExpenseException memberLeft(UUID expenseId) {
        return new GroupExpenseException(
                HttpStatus.CONFLICT,
                "group_expense_member_left",
                "Expense " + expenseId + " moves the balance of someone who has left the group.");
    }

    /** Also for someone who left the group, or who was never in it, so that who is in it is not revealed. */
    static GroupExpenseException memberNotFound(UUID userId) {
        return new GroupExpenseException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "member_not_found",
                "There is no member " + userId + " in the group.");
    }

    // The code passed the schema's pattern (three capital letters), so it is safe in a message.
    static GroupExpenseException currencyMismatch(String currencyCode) {
        return new GroupExpenseException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "currency_mismatch",
                "The amount is in " + currencyCode + ", not in the group's currency.");
    }

    // The same code as the schema rules' failures (api/openapi.yaml, BadRequest).
    static GroupExpenseException badRequest(String message) {
        return new GroupExpenseException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }
}
