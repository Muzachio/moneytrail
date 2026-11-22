package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.shared.DomainException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * A split that cannot be made from what the person entered. The codes reach the API with the group
 * expenses (#88), and the frontend's preview (#92) runs the same test cases, so it refuses the same
 * splits.
 */
final class SplitException extends DomainException {

    private SplitException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    static SplitException withoutMembers() {
        return new SplitException("split_without_members", "A split needs at least one person.");
    }

    static SplitException memberTwice(UUID memberId) {
        return new SplitException("split_member_twice", "Member " + memberId + " is in the split twice.");
    }

    static SplitException inputNotPositive(UUID memberId) {
        return new SplitException(
                "split_input_not_positive",
                "The share, percentage or amount of member " + memberId + " is not above zero.");
    }

    static SplitException percentagesNot100() {
        return new SplitException("split_percentages_not_100", "The percentages do not add up to 100 %.");
    }

    static SplitException amountsNotTotal() {
        return new SplitException("split_amounts_not_total", "The amounts do not add up to the expense's total.");
    }

    static SplitException tooManyParts() {
        return new SplitException("split_too_many_parts", "The shares add up to more than " + Splits.MAX_WEIGHT + ".");
    }
}
