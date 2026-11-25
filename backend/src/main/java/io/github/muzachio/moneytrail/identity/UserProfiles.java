package io.github.muzachio.moneytrail.identity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Looks up the {@link UserProfile} of users, for modules that show one person to another. */
public interface UserProfiles {

    /** The profiles of the users that exist among {@code userIds}, by id. */
    Map<UUID, UserProfile> find(Collection<UUID> userIds);
}
