package io.github.muzachio.moneytrail.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Currency;
import java.util.function.Supplier;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/**
 * jqwik generates the amounts, including the edges (0, ±1, {@code Long.MIN_VALUE}, {@code
 * Long.MAX_VALUE}). {@link BigInteger} is the reference: it never overflows, so it tells what the
 * exact result is and whether a {@code long} can hold it.
 */
class MoneyProperties {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Property
    void plusIsExactOrFails(@ForAll long a, @ForAll long b) {
        assertExactOrFails(() -> eur(a).plus(eur(b)), BigInteger.valueOf(a).add(BigInteger.valueOf(b)));
    }

    @Property
    void minusIsExactOrFails(@ForAll long a, @ForAll long b) {
        assertExactOrFails(() -> eur(a).minus(eur(b)), BigInteger.valueOf(a).subtract(BigInteger.valueOf(b)));
    }

    @Property
    void negateIsExactOrFails(@ForAll long a) {
        assertExactOrFails(() -> eur(a).negate(), BigInteger.valueOf(a).negate());
    }

    @Property
    void plusIsCommutative(@ForAll long a, @ForAll long b) {
        Assume.that(fitsInLong(BigInteger.valueOf(a).add(BigInteger.valueOf(b))));

        assertThat(eur(a).plus(eur(b))).isEqualTo(eur(b).plus(eur(a)));
    }

    @Property
    void plusIsAssociative(
            @ForAll @LongRange(min = Long.MIN_VALUE / 3, max = Long.MAX_VALUE / 3) long a,
            @ForAll @LongRange(min = Long.MIN_VALUE / 3, max = Long.MAX_VALUE / 3) long b,
            @ForAll @LongRange(min = Long.MIN_VALUE / 3, max = Long.MAX_VALUE / 3) long c) {
        assertThat(eur(a).plus(eur(b)).plus(eur(c))).isEqualTo(eur(a).plus(eur(b).plus(eur(c))));
    }

    @Property
    void zeroChangesNothing(@ForAll long a, @ForAll("currencies") Currency currency) {
        Money money = new Money(a, currency);

        assertThat(money.plus(Money.zero(currency))).isEqualTo(money);
        assertThat(money.minus(Money.zero(currency))).isEqualTo(money);
    }

    @Property
    void minusUndoesPlus(@ForAll long a, @ForAll long b) {
        Assume.that(fitsInLong(BigInteger.valueOf(a).add(BigInteger.valueOf(b))));

        assertThat(eur(a).plus(eur(b)).minus(eur(b))).isEqualTo(eur(a));
    }

    @Property
    void anAmountPlusItsNegationIsZero(@ForAll long a) {
        Assume.that(a != Long.MIN_VALUE);

        assertThat(eur(a).plus(eur(a).negate()).isZero()).isTrue();
    }

    @Property
    void mixingCurrenciesFails(
            @ForAll long a,
            @ForAll long b,
            @ForAll("currencies") Currency first,
            @ForAll("currencies") Currency second) {
        Assume.that(!first.equals(second));
        Money left = new Money(a, first);
        Money right = new Money(b, second);

        assertThatThrownBy(() -> left.plus(right)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> left.minus(right)).isInstanceOf(IllegalArgumentException.class);
    }

    @Provide
    Arbitrary<Currency> currencies() {
        return Arbitraries.of(Currency.getAvailableCurrencies());
    }

    private static void assertExactOrFails(Supplier<Money> operation, BigInteger exact) {
        if (fitsInLong(exact)) {
            assertThat(operation.get()).isEqualTo(eur(exact.longValueExact()));
        } else {
            assertThatThrownBy(operation::get).isInstanceOf(ArithmeticException.class);
        }
    }

    private static boolean fitsInLong(BigInteger value) {
        return value.bitLength() < Long.SIZE;
    }

    private static Money eur(long minor) {
        return new Money(minor, EUR);
    }
}
