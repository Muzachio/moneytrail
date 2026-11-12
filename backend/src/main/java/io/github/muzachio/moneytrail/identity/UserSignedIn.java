package io.github.muzachio.moneytrail.identity;

import java.util.UUID;

/**
 * Someone signed in, the first time or again. Published inside the sign-in transaction, so a
 * listener that sets up what the user needs from their first request on (their personal ledger and
 * categories) uses {@code @EventListener}: it runs at once, in that transaction, and if it fails
 * the sign-in fails with nothing half done. Published on every sign-in, not only the first, so
 * users who existed before a listener did get its state on their next sign-in; listeners must
 * therefore be idempotent (docs/architecture.md, "Module boundaries").
 */
public record UserSignedIn(UUID userId) {}
