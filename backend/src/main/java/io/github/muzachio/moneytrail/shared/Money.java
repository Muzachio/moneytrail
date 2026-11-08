package io.github.muzachio.moneytrail.shared;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount in the minor units of one currency, such as 1250 for €12.50. Integer arithmetic only,
 * never {@code double}: a result that would overflow a {@code long} throws {@link
 * ArithmeticException}, and combining two currencies throws {@link IllegalArgumentException}, so a
 * wrong amount is never returned.
 */
public record Money(long minor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    /** The opposite amount, which is what a reversal posts. */
    public Money negate() {
        return new Money(Math.negateExact(minor), currency);
    }

    public boolean isZero() {
        return minor == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot combine " + currency.getCurrencyCode() + " with " + other.currency.getCurrencyCode());
        }
    }
}
