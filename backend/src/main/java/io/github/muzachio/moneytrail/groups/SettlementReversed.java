package io.github.muzachio.moneytrail.groups;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * A member reversed a settlement without correcting it, so it no longer counts. Published in the
 * transaction that posts the reversal (see {@link SettlementRecorded}); an edit publishes {@link
 * SettlementEdited} instead.
 *
 * @param settlementId the version that was reversed
 * @param amount and {@code from}, {@code to}: those of that version
 */
public record SettlementReversed(
        UUID groupId, UUID settlementId, Money amount, UUID from, UUID to, UUID reversedBy, Instant reversedAt) {}
