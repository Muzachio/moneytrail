package io.github.muzachio.moneytrail.identity.internal;

import io.github.muzachio.moneytrail.shared.api.IdentityApi;
import io.github.muzachio.moneytrail.shared.api.model.CurrentUser;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CurrentUserController implements IdentityApi {

    private final Users users;

    CurrentUserController(Users users) {
        this.users = users;
    }

    @Override
    public ResponseEntity<CurrentUser> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SignedInUser signedIn)) {
            throw new UnauthenticatedException();
        }
        // A session can outlive its user row only if the user was deleted; treat it as signed out.
        Users.User user = users.find(signedIn.userId()).orElseThrow(UnauthenticatedException::new);
        CurrentUser body = new CurrentUser(user.id(), user.email(), user.name());
        if (user.avatarUrl() != null) {
            body.avatarUrl(URI.create(user.avatarUrl()));
        }
        return ResponseEntity.ok(body);
    }
}
