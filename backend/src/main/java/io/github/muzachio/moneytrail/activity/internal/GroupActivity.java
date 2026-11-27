package io.github.muzachio.moneytrail.activity.internal;

import io.github.muzachio.moneytrail.activity.internal.ActivityStore.Position;
import io.github.muzachio.moneytrail.activity.internal.ActivityStore.Stored;
import io.github.muzachio.moneytrail.groups.GroupMembership;
import io.github.muzachio.moneytrail.identity.UserProfile;
import io.github.muzachio.moneytrail.identity.UserProfiles;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads a group's feed, for its members only. */
@Service
class GroupActivity {

    /**
     * A page of the feed.
     *
     * @param people the profile of everyone the items name, in the order they first appear
     * @param nextCursor the cursor of the next page, or null on the last page
     */
    record Page(
            List<Stored> items,
            List<UserProfile> people,
            @Nullable String nextCursor) {

        Page {
            items = List.copyOf(items);
            people = List.copyOf(people);
        }
    }

    private final GroupMembership membership;
    private final ActivityStore store;
    private final UserProfiles profiles;

    GroupActivity(GroupMembership membership, ActivityStore store, UserProfiles profiles) {
        this.membership = membership;
        this.store = store;
        this.profiles = profiles;
    }

    /**
     * Someone who is not a member now gets the groups module's {@code 404}, before the cursor is even
     * read, so a former member learns nothing more than a stranger does.
     */
    @Transactional(readOnly = true)
    Page page(UUID userId, UUID groupId, @Nullable String cursor, int limit) {
        membership.requireMember(groupId, userId);
        Position after = cursor == null ? null : FeedCursors.decode(cursor);
        // One more than a page, to know whether another page follows.
        List<Stored> rows = store.page(groupId, after, limit + 1);
        boolean more = rows.size() > limit;
        List<Stored> items = more ? rows.subList(0, limit) : rows;
        String nextCursor = more ? FeedCursors.encode(position(items.getLast())) : null;
        return new Page(items, peopleIn(items), nextCursor);
    }

    private List<UserProfile> peopleIn(List<Stored> items) {
        SequencedSet<UUID> ids = new LinkedHashSet<>();
        for (Stored item : items) {
            Activity activity = item.activity();
            ids.add(activity.actorId());
            addIfPresent(ids, activity.paidBy());
            addIfPresent(ids, activity.from());
            addIfPresent(ids, activity.to());
        }
        Map<UUID, UserProfile> found = profiles.find(ids);
        List<UserProfile> people = new ArrayList<>();
        for (UUID id : ids) {
            UserProfile profile = found.get(id);
            if (profile != null) {
                people.add(profile);
            }
        }
        return people;
    }

    private static void addIfPresent(SequencedSet<UUID> ids, @Nullable UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private static Position position(Stored item) {
        return new Position(item.activity().occurredAt(), item.id());
    }
}
