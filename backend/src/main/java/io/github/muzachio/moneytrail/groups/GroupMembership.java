package io.github.muzachio.moneytrail.groups;

import java.util.Currency;
import java.util.UUID;

/**
 * Who may see a group's data, for modules that show some of it, such as the activity feed (#90):
 * its members now, the same rule as every endpoint of this module.
 */
public interface GroupMembership {

    /**
     * Returns when the user is a member of the group now. Otherwise it throws the problem every group
     * endpoint answers, {@code 404 group_not_found}: for a former member and for a group that does not
     * exist alike, so whether a group exists is never revealed.
     */
    void requireMember(UUID groupId, UUID userId);

    /**
     * The member's account in the group's ledger, for a module that follows what the group's entries
     * mean for one member, such as the mirror in a personal ledger (#94, ADR-0015). Throws as {@link
     * #requireMember} does for anyone who is not a member now.
     */
    MemberAccount memberAccount(UUID groupId, UUID userId);

    /**
     * A member's account in a group's ledger.
     *
     * @param accountId the member's {@code GROUP_MEMBER} account: positive, the group owes them
     * @param groupName the group's name now
     * @param currency every amount of the group is in it
     */
    record MemberAccount(UUID accountId, String groupName, Currency currency) {}
}
