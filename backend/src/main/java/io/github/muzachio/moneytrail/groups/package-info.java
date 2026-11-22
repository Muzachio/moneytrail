/**
 * Groups of friends who share expenses: members, invites, how each expense is split, and the
 * payments that settle up, on a ledger of each group's own (ADR-0012, ADR-0013).
 */
@ApplicationModule(displayName = "Groups")
@NullMarked
package io.github.muzachio.moneytrail.groups;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
