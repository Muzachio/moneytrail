/**
 * What happened in each group, newest first: an item per event of the groups module, written by a
 * listener through the outbox after the event's transaction commits (ADR-0014). The groups module
 * knows nothing of it.
 */
@ApplicationModule(displayName = "Activity")
@NullMarked
package io.github.muzachio.moneytrail.activity;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
