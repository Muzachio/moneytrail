package io.github.muzachio.moneytrail.ledger;

/**
 * What an account holds, which decides the sign of its normal balance. Categories are accounts
 * too: {@link #EXPENSE} and {@link #INCOME}. {@code GROUP_MEMBER} arrives with groups in M2.
 */
public enum AccountKind {
    /** Checking, savings, cash. Normal balance: debit (+). */
    ASSET,
    /** Credit card. Normal balance: credit (−). */
    LIABILITY,
    /** Salary, refunds. Normal balance: credit (−). */
    INCOME,
    /** Food, transport. Normal balance: debit (+). */
    EXPENSE,
    /** Opening balances. Normal balance: credit (−). */
    EQUITY
}
