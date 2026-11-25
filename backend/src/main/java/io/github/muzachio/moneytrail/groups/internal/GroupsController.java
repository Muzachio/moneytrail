package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.groups.internal.GroupStore.Row;
import io.github.muzachio.moneytrail.identity.SignedIn;
import io.github.muzachio.moneytrail.shared.api.GroupsApi;
import io.github.muzachio.moneytrail.shared.api.model.CurrencyCode;
import io.github.muzachio.moneytrail.shared.api.model.Group;
import io.github.muzachio.moneytrail.shared.api.model.GroupList;
import io.github.muzachio.moneytrail.shared.api.model.GroupMember;
import io.github.muzachio.moneytrail.shared.api.model.GroupSummary;
import io.github.muzachio.moneytrail.shared.api.model.GroupUpdate;
import io.github.muzachio.moneytrail.shared.api.model.NewGroup;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/groups} takes the {@code Idempotency-Key} header (api/openapi.yaml), so
 * shared.idempotency runs it once per key, in one transaction with the stored response.
 */
@RestController
class GroupsController implements GroupsApi {

    private final Groups groups;

    GroupsController(Groups groups) {
        this.groups = groups;
    }

    @Override
    public ResponseEntity<GroupList> listGroups() {
        return ResponseEntity.ok(new GroupList(groups.list(SignedIn.userId()).stream()
                .map(listed -> {
                    Row group = listed.group();
                    GroupSummary summary = new GroupSummary(
                            group.id(), group.name(), toApi(group.currency()), listed.memberCount(), group.archived());
                    Instant archivedAt = group.archivedAt();
                    if (archivedAt != null) {
                        summary.archivedAt(utc(archivedAt));
                    }
                    return summary;
                })
                .toList()));
    }

    @Override
    public ResponseEntity<Group> createGroup(String idempotencyKey, NewGroup newGroup) {
        CurrencyCode currency = newGroup.getCurrency();
        Groups.Detail created = groups.create(
                SignedIn.userId(),
                newGroup.getName(),
                Currency.getInstance((currency == null ? CurrencyCode.EUR : currency).getValue()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toApi(created));
    }

    @Override
    public ResponseEntity<Group> getGroup(UUID id) {
        return ResponseEntity.ok(toApi(groups.get(SignedIn.userId(), id)));
    }

    @Override
    public ResponseEntity<Group> updateGroup(UUID id, GroupUpdate update) {
        return ResponseEntity.ok(toApi(groups.update(SignedIn.userId(), id, update.getName(), update.getArchived())));
    }

    @Override
    public ResponseEntity<Void> leaveGroup(UUID id) {
        groups.leave(SignedIn.userId(), id);
        return ResponseEntity.noContent().build();
    }

    static Group toApi(Groups.Detail detail) {
        Row group = detail.group();
        Group body = new Group(
                group.id(),
                group.name(),
                toApi(group.currency()),
                group.archived(),
                utc(group.createdAt()),
                detail.members().stream().map(GroupsController::toApi).toList());
        Instant archivedAt = group.archivedAt();
        if (archivedAt != null) {
            body.archivedAt(utc(archivedAt));
        }
        return body;
    }

    private static GroupMember toApi(Groups.MemberProfile member) {
        GroupMember body = new GroupMember(member.userId(), member.name(), utc(member.joinedAt()));
        String avatarUrl = member.avatarUrl();
        if (avatarUrl != null) {
            body.avatarUrl(URI.create(avatarUrl));
        }
        return body;
    }

    static CurrencyCode toApi(Currency currency) {
        return CurrencyCode.fromValue(currency.getCurrencyCode());
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
