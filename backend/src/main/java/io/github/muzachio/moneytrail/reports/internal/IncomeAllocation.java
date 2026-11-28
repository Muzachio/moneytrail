package io.github.muzachio.moneytrail.reports.internal;

import io.github.muzachio.moneytrail.personal.AccountUse;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Where a period's income went: spent, invested and saved. The three parts always add up to the
 * income.
 *
 * @param invested how much the investment accounts changed, negative when money came out of them
 * @param saved everything neither spent nor invested, negative when more went out than came in
 * @param savingsAccounts the part of {@code saved} in the savings accounts, negative when money was
 *     taken out of them
 */
record IncomeAllocation(Money income, Money spending, Money invested, Money saved, Money savingsAccounts) {

    /**
     * {@code saved} is what remains of the income, so the parts add up to it by construction. In a
     * personal ledger it is also what the everyday and savings accounts changed together, since the
     * accounts' changes add up to income less spending ({@link AccountChangeSums}); the tests check
     * that both agree.
     *
     * @param sums the categories' sums of the period, income ones negative as the ledger credits them
     * @param changes how much each of the user's accounts changed in the period
     * @param uses what each of the user's accounts is for; an account not in it is not the user's
     */
    static IncomeAllocation of(
            Collection<CategorySum> sums, Map<UUID, Money> changes, Map<UUID, AccountUse> uses, Money zero) {
        Money income = zero;
        Money spending = zero;
        for (CategorySum sum : sums) {
            switch (sum.kind()) {
                case INCOME -> income = income.minus(sum.amount());
                case EXPENSE -> spending = spending.plus(sum.amount());
            }
        }
        Money invested = changeOf(AccountUse.INVESTMENT, changes, uses, zero);
        return new IncomeAllocation(
                income,
                spending,
                invested,
                income.minus(spending).minus(invested),
                changeOf(AccountUse.SAVINGS, changes, uses, zero));
    }

    private static Money changeOf(AccountUse use, Map<UUID, Money> changes, Map<UUID, AccountUse> uses, Money zero) {
        return changes.entrySet().stream()
                .filter(change -> uses.get(change.getKey()) == use)
                .map(Map.Entry::getValue)
                .reduce(zero, Money::plus);
    }
}
