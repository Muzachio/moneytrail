package io.github.muzachio.moneytrail.groups.internal;

import static io.github.muzachio.moneytrail.groups.internal.GroupsController.utc;

import io.github.muzachio.moneytrail.identity.SignedIn;
import io.github.muzachio.moneytrail.identity.UserProfile;
import io.github.muzachio.moneytrail.shared.api.InvitesApi;
import io.github.muzachio.moneytrail.shared.api.model.Group;
import io.github.muzachio.moneytrail.shared.api.model.InviteLink;
import io.github.muzachio.moneytrail.shared.api.model.InviteList;
import io.github.muzachio.moneytrail.shared.api.model.InvitePreview;
import io.github.muzachio.moneytrail.shared.api.model.InviteToken;
import io.github.muzachio.moneytrail.shared.api.model.NewInviteLink;
import io.github.muzachio.moneytrail.shared.api.model.Profile;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The token only ever arrives in a request body and leaves in the response that makes it (ADR-0013):
 * no path or query here carries one, and nothing logs a body.
 */
@RestController
class InvitesController implements InvitesApi {

    private final GroupInvites invites;

    InvitesController(GroupInvites invites) {
        this.invites = invites;
    }

    @Override
    public ResponseEntity<NewInviteLink> createInvite(UUID id) {
        GroupInvites.NewLink link = invites.create(SignedIn.userId(), id);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new NewInviteLink(
                        link.invite().id(),
                        link.token(),
                        utc(link.invite().createdAt()),
                        utc(link.invite().expiresAt())));
    }

    @Override
    public ResponseEntity<InviteList> listInvites(UUID id) {
        return ResponseEntity.ok(new InviteList(invites.list(SignedIn.userId(), id).stream()
                .map(link -> new InviteLink(
                        link.invite().id(),
                        toApi(link.createdBy()),
                        utc(link.invite().createdAt()),
                        utc(link.invite().expiresAt())))
                .toList()));
    }

    @Override
    public ResponseEntity<Void> revokeInvite(UUID id, UUID inviteId) {
        invites.revoke(SignedIn.userId(), id, inviteId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<InvitePreview> lookUpInvite(InviteToken token) {
        GroupInvites.Preview preview = invites.lookUp(SignedIn.userId(), token.getToken());
        return ResponseEntity.ok(new InvitePreview(
                preview.group().name(),
                GroupsController.toApi(preview.group().currency()),
                preview.memberCount(),
                toApi(preview.invitedBy()),
                preview.alreadyMember()));
    }

    @Override
    public ResponseEntity<Group> acceptInvite(InviteToken token) {
        return ResponseEntity.ok(GroupsController.toApi(invites.accept(SignedIn.userId(), token.getToken())));
    }

    private static Profile toApi(UserProfile profile) {
        Profile body = new Profile(profile.id(), profile.name());
        String avatarUrl = profile.avatarUrl();
        if (avatarUrl != null) {
            body.avatarUrl(URI.create(avatarUrl));
        }
        return body;
    }
}
