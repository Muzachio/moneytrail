package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A settlement a member records, or the correction of one: {@code from} paid {@code to} the amount,
 * outside the app. {@link Settlements} checks it against the group: its currency and its members.
 */
record NewSettlement(Money amount, LocalDate occurredOn, UUID from, UUID to) {

    NewSettlement {
        // The schema asks for an amount above zero too; this keeps the rule true for every caller.
        if (amount.minor() <= 0) {
            throw SettlementException.badRequest("The amount must be above zero.");
        }
        if (from.equals(to)) {
            throw SettlementException.toSelf();
        }
    }
}
