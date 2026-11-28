package io.github.muzachio.moneytrail.reports.internal;

import io.github.muzachio.moneytrail.personal.PersonalLedgerQueries;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user's spending reports, read from their personal ledger only: the ledger's id comes from the
 * signed-in user, never from the request, so no other ledger can be asked for.
 *
 * <p>One read-only transaction, so the sums and the labels come from the same moment. A user without
 * a ledger yet (a session from before #42) gets zeros: a report never opens one.
 */
@Service
@Transactional(readOnly = true)
class SpendingReports {

    private final PersonalLedgerQueries personalLedgers;
    private final CategorySums categorySums;
    private final AccountChangeSums accountChanges;

    SpendingReports(
            PersonalLedgerQueries personalLedgers, CategorySums categorySums, AccountChangeSums accountChanges) {
        this.personalLedgers = personalLedgers;
        this.categorySums = categorySums;
        this.accountChanges = accountChanges;
    }

    SpendingByCategory byCategory(UUID userId, ReportPeriod period) {
        return personalLedgers
                .ledgerOf(userId)
                .map(ledgerId -> SpendingByCategory.of(
                        categorySums.byMonth(ledgerId, period), personalLedgers.categoryLabels(ledgerId), zero()))
                .orElseGet(() -> SpendingByCategory.of(List.of(), Map.of(), zero()));
    }

    SpendingByMonth byMonth(UUID userId, ReportPeriod period) {
        List<CategorySum> sums = personalLedgers
                .ledgerOf(userId)
                .map(ledgerId -> categorySums.byMonth(ledgerId, period))
                .orElseGet(List::of);
        return SpendingByMonth.of(period, sums, zero());
    }

    /** The same category sums as the other reports, so its income and spending are theirs. */
    IncomeAllocation allocation(UUID userId, ReportPeriod period) {
        return personalLedgers
                .ledgerOf(userId)
                .map(ledgerId -> IncomeAllocation.of(
                        categorySums.byMonth(ledgerId, period),
                        accountChanges.between(ledgerId, period.from(), period.to()),
                        personalLedgers.accountUses(ledgerId),
                        zero()))
                .orElseGet(() -> IncomeAllocation.of(List.of(), Map.of(), Map.of(), zero()));
    }

    private Money zero() {
        return Money.zero(personalLedgers.currency());
    }
}
