package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.groups.GroupCreated;
import io.github.muzachio.moneytrail.groups.MemberLeft;
import io.github.muzachio.moneytrail.groups.internal.GroupStore.Lock;
import io.github.muzachio.moneytrail.groups.internal.GroupStore.Row;
import io.github.muzachio.moneytrail.identity.UserProfile;
import io.github.muzachio.moneytrail.identity.UserProfiles;
import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.Ledger;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import io.github.muzachio.moneytrail.shared.Money;
import io.github.muzachio.moneytrail.shared.TypedText;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Groups and their members (ADR-0012). Only members reach a group: for anyone else, someone who left
 * included, it does not exist ({@code group_not_found}).
 */
@Service
@Transactional
class Groups {

    /**
     * The name of every member account. The ledger needs one, but the person behind an account is
     * found through {@code group_members}, never through its name.
     */
    static final String MEMBER_ACCOUNT = "Member";

    record Detail(Row group, List<MemberProfile> members) {}

    record MemberProfile(UUID userId, String name, @Nullable String avatarUrl, Instant joinedAt) {}

    /** A group in the user's list, with what the group owes the user (see {@link Balances.MemberBalance}). */
    record Summary(Row group, int memberCount, Money myBalance) {}

    private final GroupStore store;
    private final LedgerService ledger;
    private final UserProfiles profiles;
    private final ApplicationEventPublisher events;

    Groups(GroupStore store, LedgerService ledger, UserProfiles profiles, ApplicationEventPublisher events) {
        this.store = store;
        this.ledger = ledger;
        this.profiles = profiles;
        this.events = events;
    }

    /** The balances come from the ledger in one read for all the groups. */
    @Transactional(readOnly = true)
    List<Summary> list(UUID userId) {
        List<GroupStore.Listed> listed = store.listForUser(userId);
        Map<UUID, Money> balances = ledger.balancesOf(
                listed.stream().map(GroupStore.Listed::accountId).toList());
        return listed.stream()
                .map(group -> new Summary(
                        group.group(),
                        group.memberCount(),
                        Objects.requireNonNull(
                                balances.get(group.accountId()),
                                () -> "Member account " + group.accountId() + " has no balance")))
                .toList();
    }

    /**
     * Opens the group's ledger and the creator's member account with the group, in one transaction,
     * so a group never exists without them. Under {@code Idempotency-Key} it is also the transaction
     * that stores the response, so a retry finds all of it or none.
     */
    Detail create(UUID userId, String name, Currency currency) {
        UUID groupId = store.newId();
        Ledger groupLedger = ledger.openLedger(LedgerOwner.group(groupId));
        Account account = ledger.openAccount(
                new NewAccount(groupLedger.id(), AccountKind.GROUP_MEMBER, null, MEMBER_ACCOUNT, currency));
        Row group = store.insert(groupId, clean(name), currency, groupLedger.id(), userId);
        store.addMember(groupId, userId, account.id());
        events.publishEvent(new GroupCreated(groupId, group.name(), userId, group.createdAt()));
        return detail(group);
    }

    @Transactional(readOnly = true)
    Detail get(UUID userId, UUID groupId) {
        return detail(member(userId, groupId, Lock.NONE));
    }

    /**
     * Locks the group's row first: archiving checks that every balance is zero, and an expense or a
     * payment must not change one meanwhile (see {@link Lock}).
     */
    Detail update(UUID userId, UUID groupId, @Nullable String name, @Nullable Boolean archived) {
        if (name == null && archived == null) {
            throw GroupException.badRequest("Send a name, archived, or both.");
        }
        Row group = member(userId, groupId, Lock.UPDATE);
        if (name != null) {
            group = store.rename(groupId, clean(name));
        }
        if (Boolean.TRUE.equals(archived)) {
            if (!ledger.balances(group.ledgerId()).values().stream().allMatch(Money::isZero)) {
                throw GroupException.notSettled();
            }
            group = store.archive(groupId);
        } else if (Boolean.FALSE.equals(archived)) {
            group = store.restore(groupId);
        }
        return detail(group);
    }

    /**
     * Only with a balance of zero, so leaving takes no money out of the group. The member's row stays,
     * with the time they left, and so does their account (ADR-0012).
     */
    void leave(UUID userId, UUID groupId) {
        GroupStore.Member membership =
                store.lockMembership(groupId, userId).orElseThrow(() -> GroupException.notFound(groupId));
        if (!ledger.balance(membership.accountId()).isZero()) {
            throw GroupException.memberNotSettled();
        }
        Instant leftAt = store.markLeft(groupId, userId);
        events.publishEvent(new MemberLeft(groupId, userId, leftAt));
    }

    /**
     * The group, for an expense or a payment that a member records in it (#88, #89): locked {@code FOR
     * SHARE} until the transaction ends, so it cannot be archived meanwhile, and refused when it is
     * archived already.
     */
    Row forPosting(UUID userId, UUID groupId) {
        Row group = member(userId, groupId, Lock.SHARE);
        if (group.archived()) {
            throw GroupException.archived(groupId);
        }
        return group;
    }

    private Row member(UUID userId, UUID groupId, Lock lock) {
        return store.findForMember(groupId, userId, lock).orElseThrow(() -> GroupException.notFound(groupId));
    }

    private Detail detail(Row group) {
        List<GroupStore.Member> members = store.members(group.id());
        Map<UUID, UserProfile> byId =
                profiles.find(members.stream().map(GroupStore.Member::userId).toList());
        return new Detail(
                group,
                members.stream()
                        .map(member -> {
                            UserProfile profile = byId.get(member.userId());
                            if (profile == null) {
                                throw new IllegalStateException("Member " + member.userId() + " is not a user");
                            }
                            return new MemberProfile(
                                    member.userId(), profile.name(), profile.avatarUrl(), member.joinedAt());
                        })
                        .toList());
    }

    // The schema refuses blank names, but Java's validator reads its \s as ASCII spaces only.
    private static String clean(String name) {
        String stripped = TypedText.strip(name);
        if (stripped.isEmpty()) {
            throw GroupException.badRequest("A group needs a name.");
        }
        return stripped;
    }
}
