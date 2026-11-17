package io.github.muzachio.moneytrail.identity.internal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Who may sign in. Locally anyone with a verified Google email, so a fresh clone works without a
 * list. Production is invite-only (application-prod.yml), with the list in the Fly secret
 * MONEYTRAIL_INVITED_EMAILS, so no email ever reaches the repository (docs/deploy.md).
 *
 * @param inviteOnly only the invited emails get in; with an empty list nobody does
 * @param invitedEmails the Google accounts' emails, in any case; blanks are ignored
 */
@ConfigurationProperties("moneytrail.sign-in")
record SignInProperties(
        @DefaultValue("false") boolean inviteOnly,
        @DefaultValue List<String> invitedEmails) {}
