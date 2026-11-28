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
    INVESTMENT,
    /**
     * A group's account (#94, ADR-0015): what the friends owe the user, or the user owes them. Neither
     * savings nor investment; the summary's "saved", what remains of the income, includes it, since
     * what is owed to the user is still theirs and what they owe already lowers it.
     */
    GROUP
}
