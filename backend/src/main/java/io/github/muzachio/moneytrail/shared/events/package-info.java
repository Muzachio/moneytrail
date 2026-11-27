/**
 * The outbox's upkeep (ADR-0005, ADR-0014): resubmitting the event publications whose listener did
 * not complete, and counting them for a metric.
 */
@NullMarked
package io.github.muzachio.moneytrail.shared.events;

import org.jspecify.annotations.NullMarked;
