package io.github.muzachio.moneytrail.groups;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A member recorded a settlement in a group: {@code from} paid {@code to}. Published in the
 * transaction that posts it, like {@link GroupExpenseAdded}, so a listener through the outbox, such
 * as the activity feed (#90), hears of it if and only if the settlement exists. It carries what such
 * a listener shows, and no email.
 *
 * @param settlementId the id of the settlement, which is its journal entry's
 * @param recordedBy who recorded it, who may be neither {@code from} nor {@code to}
 */
public record SettlementRecorded(
        UUID groupId,
        UUID settlementId,
        Money amount,
        LocalDate occurredOn,
        UUID from,
        UUID to,
        UUID recordedBy,
        Instant recordedAt) {}
