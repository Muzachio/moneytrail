package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.groups.internal.Split.MemberShare;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Divides an expense among the people in it (ADR-0012). Equal splits, shares and percentages are
 * whole-number weights divided by the largest-remainder method; exact amounts are used as they are.
 * Only {@code long} arithmetic, no {@code BigDecimal}: the frontend previews the split with {@code
 * BigInt} and must show the same cents, which integers on both sides make simple.
 */
final class Splits {

    /** Percentages are basis points: 10 000 is 100 %. */
    static final long WHOLE = 10_000;

    /**
     * The most the weights may add up to. It keeps {@code r × w} below {@code W²}, far inside a
     * {@code long}, however large the total.
     */
    static final long MAX_WEIGHT = 10_000;

    /**
     * Ties go to the id that comes first as text, the order TypeScript and Postgres use. {@code
     * UUID.compareTo} compares the two halves as signed numbers, so it would put an id starting with
     * 8 or more before one starting with 0.
     */
    private static final Comparator<SplitInput> BY_ID_AS_TEXT =
            Comparator.comparing(input -> input.memberId().toString());

    private Splits() {}

    /**
     * Divides {@code total} among the people in {@code inputs}.
     *
     * @throws SplitException when the inputs cannot make a split of {@code total}
     * @throws IllegalArgumentException for a total of zero or less, or an equal split with a weight
     *     other than 1: the expense and the API check those before a split is made
     */
    static Split divide(Money total, SplitMethod method, List<SplitInput> inputs) {
        if (total.minor() <= 0) {
            throw new IllegalArgumentException("A split needs a total above zero, not " + total.minor());
        }
        List<SplitInput> ordered = inputs.stream().sorted(BY_ID_AS_TEXT).toList();
        check(total.minor(), method, ordered);

        long[] shares = method == SplitMethod.EXACT_AMOUNTS
                ? ordered.stream().mapToLong(SplitInput::value).toArray()
                : largestRemainder(total.minor(), ordered);
        List<MemberShare> result = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            SplitInput input = ordered.get(i);
            result.add(new MemberShare(input.memberId(), input.value(), new Money(shares[i], total.currency())));
        }
        return new Split(method, total, result);
    }

    private static void check(long total, SplitMethod method, List<SplitInput> inputs) {
        if (inputs.isEmpty()) {
            throw SplitException.withoutMembers();
        }
        Set<UUID> members = new HashSet<>();
        for (SplitInput input : inputs) {
            if (!members.add(input.memberId())) {
                throw SplitException.memberTwice(input.memberId());
            }
            if (method == SplitMethod.EQUALLY && input.value() != 1) {
                throw new IllegalArgumentException("An equal split gives everyone a weight of 1, not " + input.value());
            }
            if (input.value() <= 0) {
                throw SplitException.inputNotPositive(input.memberId());
            }
        }
        OptionalLong sum = sum(inputs);
        switch (method) {
            case EQUALLY, SHARES -> {
                if (sum.isEmpty() || sum.getAsLong() > MAX_WEIGHT) {
                    throw SplitException.tooManyParts();
                }
            }
            case PERCENTAGES -> {
                if (sum.isEmpty() || sum.getAsLong() != WHOLE) {
                    throw SplitException.percentagesNot100();
                }
            }
            case EXACT_AMOUNTS -> {
                if (sum.isEmpty() || sum.getAsLong() != total) {
                    throw SplitException.amountsNotTotal();
                }
            }
        }
    }

    /**
     * Each person gets {@code ⌊T × w / W⌋}, and the minor units left over, fewer than the number of
     * people, go one each to the largest remainders. {@code T × w} is never formed, since it can
     * overflow: with {@code T = q × W + r}, a share is {@code q × w + ⌊r × w / W⌋} and its remainder
     * {@code r × w mod W}, where {@code q × w ≤ T} and {@code r × w < W²}.
     */
    private static long[] largestRemainder(long total, List<SplitInput> ordered) {
        long weights = sum(ordered).orElseThrow();
        long quotient = total / weights;
        long remainder = total % weights;
        long[] shares = new long[ordered.size()];
        long[] remainders = new long[ordered.size()];
        long leftOver = total;
        for (int i = 0; i < ordered.size(); i++) {
            long weight = ordered.get(i).value();
            shares[i] = Math.multiplyExact(quotient, weight) + (remainder * weight) / weights;
            remainders[i] = (remainder * weight) % weights;
            leftOver -= shares[i];
        }
        // The sort is stable and the inputs are already in id order, so equal remainders stay in it.
        IntStream.range(0, ordered.size())
                .boxed()
                .sorted(Comparator.comparingLong((Integer i) -> remainders[i]).reversed())
                .limit(leftOver)
                .forEach(i -> shares[i]++);
        return shares;
    }

    /**
     * Empty when the sum does not fit in a {@code long}: the inputs are all above zero by then, so
     * such a sum is past every limit and cannot equal any total.
     */
    private static OptionalLong sum(List<SplitInput> inputs) {
        long sum = 0;
        for (SplitInput input : inputs) {
            try {
                sum = Math.addExact(sum, input.value());
            } catch (ArithmeticException tooLarge) {
                return OptionalLong.empty();
            }
        }
        return OptionalLong.of(sum);
    }
}
