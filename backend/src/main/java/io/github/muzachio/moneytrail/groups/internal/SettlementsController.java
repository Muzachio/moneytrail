package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.groups.internal.SettleUpSuggestions.Suggestion;
import io.github.muzachio.moneytrail.identity.SignedIn;
import io.github.muzachio.moneytrail.shared.Money;
import io.github.muzachio.moneytrail.shared.api.GroupSettlementsApi;
import io.github.muzachio.moneytrail.shared.api.model.GroupBalances;
import io.github.muzachio.moneytrail.shared.api.model.MemberBalance;
import io.github.muzachio.moneytrail.shared.api.model.PositiveMoney;
import io.github.muzachio.moneytrail.shared.api.model.SettleUp;
import io.github.muzachio.moneytrail.shared.api.model.SettlementDetail;
import io.github.muzachio.moneytrail.shared.api.model.SettlementReversal;
import io.github.muzachio.moneytrail.shared.api.model.SuggestedPayment;
import java.util.Currency;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Both {@code POST}s take the {@code Idempotency-Key} header (api/openapi.yaml), so
 * shared.idempotency runs each of them once per key, in one transaction with the stored response.
 * The API's {@code Settlement} and {@code NewSettlement} share their names with this package's
 * records, so they are written out in full here.
 */
@RestController
class SettlementsController implements GroupSettlementsApi {

    private final Settlements settlements;
    private final Balances balances;

    SettlementsController(Settlements settlements, Balances balances) {
        this.settlements = settlements;
        this.balances = balances;
    }

    @Override
    public ResponseEntity<GroupBalances> getGroupBalances(UUID id) {
        return ResponseEntity.ok(new GroupBalances(balances.of(SignedIn.userId(), id).stream()
                .map(member -> new MemberBalance(member.userId(), GroupExpensesController.toApi(member.balance())))
                .toList()));
    }

    @Override
    public ResponseEntity<SettleUp> getSettleUp(UUID id) {
        return ResponseEntity.ok(new SettleUp(balances.settleUp(SignedIn.userId(), id).stream()
                .map(SettlementsController::toApi)
                .toList()));
    }

    @Override
    public ResponseEntity<io.github.muzachio.moneytrail.shared.api.model.Settlement> createSettlement(
            String idempotencyKey, UUID id, io.github.muzachio.moneytrail.shared.api.model.NewSettlement body) {
        Settlement recorded = settlements.record(SignedIn.userId(), id, toSettlement(body));
        return ResponseEntity.status(HttpStatus.CREATED).body(toApi(recorded));
    }

    @Override
    public ResponseEntity<SettlementDetail> getSettlement(UUID id, UUID settlementId) {
        return ResponseEntity.ok(toApi(settlements.find(SignedIn.userId(), id, settlementId)));
    }

    @Override
    public ResponseEntity<SettlementDetail> reverseSettlement(
            String idempotencyKey, UUID id, UUID settlementId, @Nullable SettlementReversal settlementReversal) {
        io.github.muzachio.moneytrail.shared.api.model.NewSettlement replacement =
                settlementReversal == null ? null : settlementReversal.getReplacement();
        NewSettlement correction = replacement == null ? null : toSettlement(replacement);
        return ResponseEntity.ok(toApi(settlements.reverse(SignedIn.userId(), id, settlementId, correction)));
    }

    static io.github.muzachio.moneytrail.shared.api.model.Settlement toApi(Settlement settlement) {
        return new io.github.muzachio.moneytrail.shared.api.model.Settlement(
                settlement.id(),
                GroupExpensesController.toApi(settlement.amount()),
                settlement.occurredOn(),
                settlement.from(),
                settlement.to(),
                settlement.recordedBy(),
                GroupsController.utc(settlement.recordedAt()),
                settlement.edited(),
                settlement.reversed());
    }

    private static SettlementDetail toApi(SettlementHistory history) {
        SettlementDetail body = new SettlementDetail(
                toApi(history.settlement()),
                history.trail().stream().map(GroupExpensesController::toApi).toList());
        UUID currentId = history.currentId();
        if (currentId != null) {
            body.currentId(currentId);
        }
        return body;
    }

    private static SuggestedPayment toApi(Suggestion suggestion) {
        return new SuggestedPayment(
                suggestion.from(), suggestion.to(), GroupExpensesController.toApi(suggestion.amount()));
    }

    private static NewSettlement toSettlement(io.github.muzachio.moneytrail.shared.api.model.NewSettlement body) {
        return new NewSettlement(toMoney(body.getAmount()), body.getOccurredOn(), body.getFrom(), body.getTo());
    }

    /**
     * The schema only checks for three capital letters, and {@link Currency} refuses a code it does
     * not know: such a code is not the group's currency either.
     */
    private static Money toMoney(PositiveMoney amount) {
        try {
            return new Money(amount.getAmountMinor(), Currency.getInstance(amount.getCurrency()));
        } catch (IllegalArgumentException unknownCurrency) {
            throw SettlementException.currencyMismatch(amount.getCurrency());
        }
    }
}
