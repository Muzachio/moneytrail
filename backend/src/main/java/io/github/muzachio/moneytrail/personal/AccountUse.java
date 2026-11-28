package io.github.muzachio.moneytrail.personal;

/**
 * What one of the user's accounts is for, as far as where their income went is concerned (#125).
 * Money moved into savings or investments is put aside, not spent; the rest stays in the accounts
 * the user lives from.
 */
public enum AccountUse {
    /** Checking, cash and credit cards: the money the user lives from, and what they owe on cards. */
    EVERYDAY,
    /** Savings accounts. */
    SAVINGS,
    /** Investment accounts (#121). */
    INVESTMENT
}
