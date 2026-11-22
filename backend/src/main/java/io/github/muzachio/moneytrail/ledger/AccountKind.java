package io.github.muzachio.moneytrail.ledger;

/**
 * What an account holds, which decides the sign of its normal balance. Categories are accounts
 * too: {@link #EXPENSE} and {@link #INCOME}. A group's ledger has only {@link #GROUP_MEMBER}
 * accounts (ADR-0012).
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
    EQUITY,
    /**
     * One member's place in a group: what the group owes them. No normal balance, since it is
     * either sign: positive when the others owe them (they paid more than their shares), negative
     * when they owe the others. The member accounts of a group add up to zero.
     */
    GROUP_MEMBER
}
