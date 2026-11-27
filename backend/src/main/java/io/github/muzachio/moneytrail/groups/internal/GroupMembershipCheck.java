package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.groups.GroupMembership;
import io.github.muzachio.moneytrail.groups.internal.GroupStore.Lock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The same lookup the endpoints of this module make before anything else. */
@Component
class GroupMembershipCheck implements GroupMembership {

    private final GroupStore store;

    GroupMembershipCheck(GroupStore store) {
        this.store = store;
    }

    @Override
    public void requireMember(UUID groupId, UUID userId) {
        if (store.findForMember(groupId, userId, Lock.NONE).isEmpty()) {
            throw GroupException.notFound(groupId);
        }
    }
}
