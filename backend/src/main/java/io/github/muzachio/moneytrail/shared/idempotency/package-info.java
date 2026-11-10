/**
 * The {@code Idempotency-Key} header (docs/architecture.md "Idempotency flow"): a retried
 * money-moving request gets the stored response of the first one instead of recording the movement
 * twice. An endpoint opts in by declaring the header in api/openapi.yaml; nothing else is needed.
 */
@NullMarked
package io.github.muzachio.moneytrail.shared.idempotency;

import org.jspecify.annotations.NullMarked;
