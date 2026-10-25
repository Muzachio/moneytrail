package io.github.muzachio.moneytrail.identity.internal;

import io.github.muzachio.moneytrail.shared.api.IdentityApi;
import io.github.muzachio.moneytrail.shared.api.model.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CurrentUserController implements IdentityApi {

    @Override
    public ResponseEntity<CurrentUser> getCurrentUser() {
        // Google sign-in arrives with issue #8; until then no request can have a session.
        throw new UnauthenticatedException();
    }
}
