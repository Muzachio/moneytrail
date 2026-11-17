package io.github.muzachio.moneytrail.identity.internal;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The invite list (SignInProperties). An invite-only app with an empty list lets nobody in: a
 * missing or empty secret must close the app, never open it to every Google account.
 */
@Component
// Only the API signs people in; the worker and the release command have no use for the list.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class Invitations {

    /** The OAuth2 error code of a refused sign-in, which SignInFailureHandler turns into a reason. */
    static final String NOT_INVITED = "not_invited";

    private static final Logger log = LoggerFactory.getLogger(Invitations.class);

    private final boolean inviteOnly;
    private final Set<String> emails;

    Invitations(SignInProperties properties) {
        this.inviteOnly = properties.inviteOnly();
        this.emails = properties.invitedEmails().stream()
                .map(Invitations::normalized)
                .filter(email -> !email.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        // The count only, never the emails: it shows after a deploy that the secret was read.
        if (inviteOnly && emails.isEmpty()) {
            log.warn("Sign-in is invite-only and no email is invited: nobody can sign in");
        } else if (inviteOnly) {
            log.info("Sign-in is invite-only: {} emails invited", emails.size());
        }
    }

    /**
     * Ignores case, like {@code users.email} (citext) and the mail providers themselves: someone
     * invited as {@code Ana@Example.com} gets in when Google says {@code ana@example.com}. Only
     * called with an email the provider has verified (SignInProfile), so nobody can claim an
     * invited address they do not own.
     */
    boolean admits(String email) {
        return !inviteOnly || emails.contains(normalized(email));
    }

    private static String normalized(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
