package io.github.muzachio.moneytrail.groups.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.groups.internal.Split.MemberShare;
import io.github.muzachio.moneytrail.shared.Money;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The test cases in {@code api/test-vectors/splits.json}, which the frontend's preview runs too
 * (#92), so both split an expense to the same cent.
 */
class SplitsTests {

    private static final Path TEST_VECTORS = Path.of("../api/test-vectors/splits.json");
    private static final Currency EUR = Currency.getInstance("EUR");

    private static final UUID FIRST = UUID.fromString("019a7c00-0000-7000-8000-00000000000a");
    private static final UUID SECOND = UUID.fromString("019a7c00-0000-7000-8000-00000000000b");
    private static final UUID LAST_AS_TEXT = UUID.fromString("8f3e0000-0000-7000-8000-000000000001");

    @TestFactory
    Stream<DynamicTest> theSharedTestCases() throws IOException {
        TestVectors vectors = JsonMapper.builder().build().readValue(Files.readString(TEST_VECTORS), TestVectors.class);

        assertThat(vectors.cases()).isNotEmpty();
        return vectors.cases().stream().map(testCase -> DynamicTest.dynamicTest(testCase.name(), () -> run(testCase)));
    }

    @Test
    void theSharesComeInTheOrderOfTheIdsAsText() {
        Split split = Splits.divide(
                eur(300),
                SplitMethod.EQUALLY,
                List.of(new SplitInput(LAST_AS_TEXT, 1), new SplitInput(SECOND, 1), new SplitInput(FIRST, 1)));

        assertThat(split.shares()).extracting(MemberShare::memberId).containsExactly(FIRST, SECOND, LAST_AS_TEXT);
        // The ids really are in a different order for UUID.compareTo, so the test means something.
        assertThat(LAST_AS_TEXT.compareTo(FIRST)).isNegative();
    }

    @Test
    void theSplitKeepsWhatEachPersonWasGiven() {
        Split split = Splits.divide(
                eur(1000), SplitMethod.SHARES, List.of(new SplitInput(FIRST, 3), new SplitInput(SECOND, 1)));

        assertThat(split.method()).isEqualTo(SplitMethod.SHARES);
        assertThat(split.total()).isEqualTo(eur(1000));
        assertThat(split.shares())
                .containsExactly(new MemberShare(FIRST, 3, eur(750)), new MemberShare(SECOND, 1, eur(250)));
    }

    @Test
    void anEqualSplitGivesEveryoneAWeightOfOne() {
        List<SplitInput> inputs = List.of(new SplitInput(FIRST, 2), new SplitInput(SECOND, 1));

        assertThatThrownBy(() -> Splits.divide(eur(1000), SplitMethod.EQUALLY, inputs))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTotalOfZeroIsNeverSplit() {
        List<SplitInput> inputs = List.of(new SplitInput(FIRST, 1));

        assertThatThrownBy(() -> Splits.divide(eur(0), SplitMethod.EQUALLY, inputs))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactAmountsTooLargeToAddUpDoNotMatchAnyTotal() {
        List<SplitInput> inputs = List.of(new SplitInput(FIRST, Long.MAX_VALUE), new SplitInput(SECOND, 1));

        assertThatThrownBy(() -> Splits.divide(eur(Long.MAX_VALUE), SplitMethod.EXACT_AMOUNTS, inputs))
                .isInstanceOfSatisfying(
                        SplitException.class, e -> assertThat(e.code()).isEqualTo("split_amounts_not_total"));
    }

    private static void run(TestCase testCase) {
        Money total = eur(testCase.total());
        List<SplitInput> inputs = testCase.inputs().stream()
                .map(input -> new SplitInput(input.member(), input.input()))
                .toList();
        String error = testCase.error();
        List<ExpectedShare> shares = testCase.shares();

        if (error != null) {
            assertThatThrownBy(() -> Splits.divide(total, testCase.method(), inputs))
                    .isInstanceOfSatisfying(
                            SplitException.class, e -> assertThat(e.code()).isEqualTo(error));
        } else if (shares != null) {
            Split split = Splits.divide(total, testCase.method(), inputs);

            assertThat(sharesByMember(split))
                    .isEqualTo(shares.stream().collect(Collectors.toMap(ExpectedShare::member, ExpectedShare::share)));
        } else {
            throw new AssertionError(testCase.name() + " has neither shares nor an error");
        }
    }

    private static Map<UUID, Long> sharesByMember(Split split) {
        return split.shares().stream()
                .collect(Collectors.toMap(
                        MemberShare::memberId, share -> share.share().minor()));
    }

    private static Money eur(long minor) {
        return new Money(minor, EUR);
    }

    record TestVectors(String about, List<TestCase> cases) {}

    record TestCase(
            String name,
            long total,
            SplitMethod method,
            List<Input> inputs,
            @Nullable List<ExpectedShare> shares,
            @Nullable String error) {}

    record Input(UUID member, long input) {}

    record ExpectedShare(UUID member, long share) {}
}
