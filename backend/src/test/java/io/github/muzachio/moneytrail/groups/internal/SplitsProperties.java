package io.github.muzachio.moneytrail.groups.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.groups.internal.Split.MemberShare;
import io.github.muzachio.moneytrail.shared.Money;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik generates totals up to {@code Long.MAX_VALUE}, up to twelve members with any ids (half of
 * them start with 8 or more, where {@code UUID.compareTo} and text disagree) and inputs for each
 * method. {@link BigInteger} is the reference: it gives each person's exact proportion {@code T × w
 * / W} without overflowing.
 */
class SplitsProperties {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Property
    void theSharesAddUpToTheTotal(@ForAll("splits") Example example) {
        Split split = example.divide();

        long sum = split.shares().stream()
                .mapToLong(share -> share.share().minor())
                .reduce(0, Math::addExact);
        assertThat(sum).isEqualTo(example.total());
    }

    @Property
    void eachShareIsLessThanOneMinorUnitFromItsExactProportion(@ForAll("weighted") Example example) {
        Split split = example.divide();
        BigInteger total = BigInteger.valueOf(example.total());
        BigInteger weights = example.weights();

        for (MemberShare share : split.shares()) {
            // |share - T × w / W| < 1, multiplied by W to stay in whole numbers.
            BigInteger distance = BigInteger.valueOf(share.share().minor())
                    .multiply(weights)
                    .subtract(total.multiply(BigInteger.valueOf(share.input())))
                    .abs();
            assertThat(distance).isLessThan(weights);
        }
    }

    @Property
    void theMinorUnitsLeftOverGoToTheLargestRemaindersThenToTheIdsFirstAsText(@ForAll("weighted") Example example) {
        Split split = example.divide();
        BigInteger total = BigInteger.valueOf(example.total());
        BigInteger weights = example.weights();
        List<Rounded> rounded = new ArrayList<>();
        for (MemberShare share : split.shares()) {
            BigInteger[] exact =
                    total.multiply(BigInteger.valueOf(share.input())).divideAndRemainder(weights);
            long extra =
                    BigInteger.valueOf(share.share().minor()).subtract(exact[0]).longValueExact();
            assertThat(extra).isBetween(0L, 1L);
            rounded.add(new Rounded(share.memberId().toString(), exact[1], extra == 1));
        }

        for (Rounded up : rounded.stream().filter(Rounded::roundedUp).toList()) {
            for (Rounded down : rounded.stream().filter(r -> !r.roundedUp()).toList()) {
                int byRemainder = up.remainder().compareTo(down.remainder());
                assertThat(byRemainder > 0 || (byRemainder == 0 && up.id().compareTo(down.id()) < 0))
                        .as("%s rounded up before %s", up, down)
                        .isTrue();
            }
        }
    }

    @Property
    void theOrderOfTheInputsChangesNothing(@ForAll("splits") Example example, @ForAll Random random) {
        List<SplitInput> shuffled = new ArrayList<>(example.inputs());
        Collections.shuffle(shuffled, random);

        assertThat(Splits.divide(new Money(example.total(), EUR), example.method(), shuffled))
                .isEqualTo(example.divide());
    }

    @Property
    void exactAmountsAreKeptAsTheyAre(@ForAll("exactAmounts") Example example) {
        Split split = example.divide();

        for (MemberShare share : split.shares()) {
            assertThat(share.share().minor()).isEqualTo(share.input());
        }
    }

    @Provide
    Arbitrary<Example> splits() {
        return Arbitraries.oneOf(weighted(), exactAmounts());
    }

    @Provide
    Arbitrary<Example> weighted() {
        return Arbitraries.oneOf(equally(), shares(), percentages());
    }

    @Provide
    Arbitrary<Example> exactAmounts() {
        // Twelve amounts of at most 10^15 add up to well under Long.MAX_VALUE.
        return members()
                .flatMap(ids -> Arbitraries.longs()
                        .between(1, 1_000_000_000_000_000L)
                        .list()
                        .ofSize(ids.size())
                        .map(amounts -> new Example(
                                amounts.stream().reduce(0L, Math::addExact),
                                SplitMethod.EXACT_AMOUNTS,
                                inputs(ids, amounts))));
    }

    private Arbitrary<Example> equally() {
        return Combinators.combine(totals(), members())
                .as((total, ids) ->
                        new Example(total, SplitMethod.EQUALLY, inputs(ids, Collections.nCopies(ids.size(), 1L))));
    }

    private Arbitrary<Example> shares() {
        return Combinators.combine(totals(), members())
                .flatAs((total, ids) -> Arbitraries.longs()
                        .between(1, 500)
                        .list()
                        .ofSize(ids.size())
                        .map(shares -> new Example(total, SplitMethod.SHARES, inputs(ids, shares))));
    }

    /** Cuts 10 000 basis points at distinct places, so every member gets at least one. */
    private Arbitrary<Example> percentages() {
        return Combinators.combine(totals(), members())
                .flatAs((total, ids) -> Arbitraries.longs()
                        .between(1, Splits.WHOLE - 1)
                        .set()
                        .ofSize(ids.size() - 1)
                        .map(cuts -> new Example(total, SplitMethod.PERCENTAGES, inputs(ids, parts(cuts)))));
    }

    private static Arbitrary<Long> totals() {
        return Arbitraries.longs().between(1, Long.MAX_VALUE);
    }

    private static Arbitrary<List<UUID>> members() {
        return Combinators.combine(Arbitraries.longs(), Arbitraries.longs())
                .as(UUID::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(12)
                .uniqueElements();
    }

    private static List<Long> parts(Set<Long> cuts) {
        List<Long> sorted = new ArrayList<>(cuts);
        Collections.sort(sorted);
        sorted.add(Splits.WHOLE);
        List<Long> parts = new ArrayList<>();
        long previous = 0;
        for (long cut : sorted) {
            parts.add(cut - previous);
            previous = cut;
        }
        return parts;
    }

    private static List<SplitInput> inputs(List<UUID> ids, List<Long> values) {
        return IntStream.range(0, ids.size())
                .mapToObj(i -> new SplitInput(ids.get(i), values.get(i)))
                .toList();
    }

    record Example(long total, SplitMethod method, List<SplitInput> inputs) {

        Split divide() {
            return Splits.divide(new Money(total, EUR), method, inputs);
        }

        BigInteger weights() {
            return inputs.stream()
                    .map(input -> BigInteger.valueOf(input.value()))
                    .reduce(BigInteger.ZERO, BigInteger::add);
        }
    }

    record Rounded(String id, BigInteger remainder, boolean roundedUp) {}
}
