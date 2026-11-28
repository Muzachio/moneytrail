package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.identity.SignedIn;
import io.github.muzachio.moneytrail.personal.internal.GroupMirrorStore.Mirror;
import io.github.muzachio.moneytrail.shared.api.GroupMirrorApi;
import io.github.muzachio.moneytrail.shared.api.model.GroupMirror;
import io.github.muzachio.moneytrail.shared.api.model.GroupMirrorSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Under a group's path, but here: a member's mirror writes only to their own ledger (ADR-0015), and
 * {@code groups} knows nothing of personal ledgers. {@code PUT} and {@code DELETE} take the {@code
 * Idempotency-Key} header, so shared.idempotency runs each once per key.
 */
@RestController
class GroupMirrorController implements GroupMirrorApi {

    private final GroupMirrors mirrors;

    GroupMirrorController(GroupMirrors mirrors) {
        this.mirrors = mirrors;
    }

    @Override
    public ResponseEntity<GroupMirror> getGroupMirror(UUID id) {
        return ResponseEntity.ok(toApi(mirrors.find(SignedIn.userId(), id)));
    }

    @Override
    public ResponseEntity<GroupMirror> turnOnGroupMirror(
            String idempotencyKey, UUID id, GroupMirrorSettings groupMirrorSettings) {
        return ResponseEntity.ok(toApi(Optional.of(mirrors.turnOn(
                SignedIn.userId(), id, groupMirrorSettings.getCategoryId(), groupMirrorSettings.getAccountId()))));
    }

    @Override
    public ResponseEntity<GroupMirror> turnOffGroupMirror(String idempotencyKey, UUID id) {
        mirrors.turnOff(SignedIn.userId(), id);
        return ResponseEntity.ok(toApi(Optional.empty()));
    }

    private static GroupMirror toApi(Optional<Mirror> mirror) {
        return mirror.map(on -> new GroupMirror(true)
                        .categoryId(on.categoryId())
                        .accountId(on.paymentAccountId())
                        .groupAccountId(on.accountId()))
                .orElseGet(() -> new GroupMirror(false));
    }
}
