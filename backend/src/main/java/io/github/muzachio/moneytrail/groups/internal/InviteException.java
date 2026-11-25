package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.shared.DomainException;
import org.springframework.http.HttpStatus;

/** The codes are listed in api/openapi.yaml (InviteNotFound, InviteNotValid). */
final class InviteException extends DomainException {

    private InviteException(String message) {
        super(HttpStatus.NOT_FOUND, "invite_not_found", message);
    }

    /** No active link of the group has this id: none, another group's, revoked or expired. */
    static InviteException notFound() {
        return new InviteException("This group has no active invite link with this id.");
    }

    /**
     * The token opens no group. The same answer for a token no link has, a revoked or expired link,
     * and an archived group, so trying tokens teaches nothing about which ones exist (ADR-0013).
     */
    static InviteException notValid() {
        return new InviteException("This invite link no longer works.");
    }
}
