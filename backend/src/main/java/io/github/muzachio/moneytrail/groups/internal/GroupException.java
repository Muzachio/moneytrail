package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.shared.DomainException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** The codes are listed in api/openapi.yaml (GroupNotFound, GroupNotSettled, BadRequest). */
final class GroupException extends DomainException {

    private GroupException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    /**
     * Also for a group the user is not in, or has left, so that whether it exists is not revealed.
     */
    static GroupException notFound(UUID groupId) {
        return new GroupException(HttpStatus.NOT_FOUND, "group_not_found", "There is no group " + groupId + ".");
    }

    static GroupException notSettled() {
        return new GroupException(
                HttpStatus.CONFLICT,
                "group_not_settled",
                "Every member's balance must be zero before the group is archived.");
    }

    static GroupException memberNotSettled() {
        return new GroupException(
                HttpStatus.CONFLICT, "member_not_settled", "Your balance in this group must be zero before you leave.");
    }

    /** For the expenses and payments of #88 and #89, which an archived group does not take. */
    static GroupException archived(UUID groupId) {
        return new GroupException(HttpStatus.CONFLICT, "group_archived", "Group " + groupId + " is archived.");
    }

    // The same code as the schema rules' failures (api/openapi.yaml, BadRequest).
    static GroupException badRequest(String message) {
        return new GroupException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }
}
