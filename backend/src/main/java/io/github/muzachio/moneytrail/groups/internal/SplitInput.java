package io.github.muzachio.moneytrail.groups.internal;

import java.util.UUID;

/**
 * What one person was given in a split, as {@code expense_splits} stores it: 1 when splitting
 * equally, their shares, their basis points, or their amount in minor units.
 */
record SplitInput(UUID memberId, long value) {}
