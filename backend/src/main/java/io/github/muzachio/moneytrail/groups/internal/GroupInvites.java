package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.groups.MemberJoined;
import io.github.muzachio.moneytrail.groups.internal.GroupStore.Lock;
import io.github.muzachio.moneytrail.groups.internal.GroupStore.Membership;
import io.github.muzachio.moneytrail.groups.internal.GroupStore.Row;
import io.github.muzachio.moneytrail.groups.internal.InviteStore.Invite;
import io.github.muzachio.moneytrail.identity.UserProfile;
import io.github.muzachio.moneytrail.identity.UserProfiles;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invite links to a group (ADR-0013). Members make, list and revoke them; anyone signed in can look
 * one up and accept it, but only with its token, and every token that opens no group gets the same
 * answer. A link lets someone into a group, never into the app: sign-in is checked before any of
 * this runs.
 */
@Service
@Transactional
class GroupInvites {

    record NewLink(Invite invite, String token) {}

    record Link(Invite invite, UserProfile createdBy) {}

    record Preview(Row group, int memberCount, UserProfile invitedBy, boolean alreadyMember) {}

    private final GroupStore groupStore;
    private final InviteStore invites;
    private final InviteTokens tokens;
    private final Groups groups;
    private final LedgerService ledger;
    private final UserProfiles profiles;
    private final ApplicationEventPublisher events;

    GroupInvites(
            GroupStore groupStore,
            InviteStore invites,
            InviteTokens tokens,
            Groups groups,
            LedgerService ledger,
            UserProfiles profiles,
            ApplicationEventPublisher events) {
        this.groupStore = groupStore;
        this.invites = invites;
        this.tokens = tokens;
        this.groups = groups;
        this.ledger = ledger;
        this.profiles = profiles;
        this.events = events;
    }

    /**
     * The group's row is locked {@code FOR SHARE}, like a posting's, so the group cannot be archived
     * while a link to it is being made.
     */
    NewLink create(UUID userId, UUID groupId) {
        Row group = groupStore
                .findForMember(groupId, userId, Lock.SHARE)
                .orElseThrow(() -> GroupException.notFound(groupId));
        if (group.archived()) {
            throw GroupException.archived(groupId);
        }
        String token = tokens.newToken();
        return new NewLink(invites.insert(groupId, InviteTokens.hash(token), userId), token);
    }

    @Transactional(readOnly = true)
    List<Link> list(UUID userId, UUID groupId) {
        requireMember(userId, groupId);
        List<Invite> active = invites.active(groupId);
        Map<UUID, UserProfile> creators =
                profiles.find(active.stream().map(Invite::createdBy).distinct().toList());
        return active.stream()
                .map(invite -> new Link(invite, profile(creators, invite.createdBy())))
                .toList();
    }

    void revoke(UUID userId, UUID groupId, UUID inviteId) {
        requireMember(userId, groupId);
        if (!invites.revoke(groupId, inviteId)) {
            throw InviteException.notFound();
        }
    }

    @Transactional(readOnly = true)
    Preview lookUp(UUID userId, String token) {
        Invite invite = invites.findActive(InviteTokens.hash(token)).orElseThrow(InviteException::notValid);
        Row group = openGroup(invite, Lock.NONE);
        boolean alreadyMember = groupStore
                .membership(group.id(), userId)
                .map(Membership::current)
                .orElse(false);
        return new Preview(
                group,
                groupStore.memberCount(group.id()),
                profile(profiles.find(List.of(invite.createdBy())), invite.createdBy()),
                alreadyMember);
    }

    /**
     * Locks the group's row {@code FOR UPDATE}, so two accepts by the same person, or an accept and
     * archiving, wait for each other instead of opening two accounts or joining an archived group.
     * Accepting as a member changes nothing; someone who left gets their old account back.
     */
    Groups.Detail accept(UUID userId, String token) {
        Invite invite = invites.findActive(InviteTokens.hash(token)).orElseThrow(InviteException::notValid);
        Row group = openGroup(invite, Lock.UPDATE);
        Optional<Membership> membership = groupStore.membership(group.id(), userId);
        if (membership.isEmpty()) {
            UUID account = ledger.openAccount(new NewAccount(
                            group.ledgerId(), AccountKind.GROUP_MEMBER, null, Groups.MEMBER_ACCOUNT, group.currency()))
                    .id();
            join(group, userId, groupStore.addMember(group.id(), userId, account));
        } else if (!membership.get().current()) {
            join(group, userId, groupStore.rejoin(group.id(), userId));
        }
        return groups.get(userId, group.id());
    }

    private void join(Row group, UUID userId, Instant joinedAt) {
        events.publishEvent(new MemberJoined(group.id(), userId, joinedAt));
    }

    /** An archived group answers like a token that opens nothing (ADR-0013). */
    private Row openGroup(Invite invite, Lock lock) {
        return groupStore
                .find(invite.groupId(), lock)
                .filter(group -> !group.archived())
                .orElseThrow(InviteException::notValid);
    }

    private void requireMember(UUID userId, UUID groupId) {
        if (groupStore.findForMember(groupId, userId, Lock.NONE).isEmpty()) {
            throw GroupException.notFound(groupId);
        }
    }

    private static UserProfile profile(Map<UUID, UserProfile> profiles, UUID userId) {
        UserProfile profile = profiles.get(userId);
        if (profile == null) {
            throw new IllegalStateException("User " + userId + " has no profile");
        }
        return profile;
    }
}
