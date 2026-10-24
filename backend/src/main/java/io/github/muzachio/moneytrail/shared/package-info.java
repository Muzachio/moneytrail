/**
 * Building blocks every module uses: {@code Money}, idempotency, web and problem handling,
 * configuration. Open, so other modules may use its internals too.
 */
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
@NullMarked
package io.github.muzachio.moneytrail.shared;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
