package io.github.muzachio.moneytrail.groups;

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
}
