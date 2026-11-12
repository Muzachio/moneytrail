package io.github.muzachio.moneytrail.identity;

import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who is making the current request, for the controllers of the other modules. */
public final class SignedIn {

    private SignedIn() {}

    /**
     * The signed-in user's id: the principal's name is the internal user id, never the email.
     * Every endpoint except the health checks requires a session, so a controller always has one.
     */
    public static UUID userId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            throw new IllegalStateException("No user is signed in");
        }
        return UUID.fromString(authentication.getName());
    }
}
