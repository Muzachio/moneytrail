package io.github.muzachio.moneytrail.groups.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.groups.internal.Balances.MemberBalance;
import io.github.muzachio.moneytrail.groups.internal.SettleUpSuggestions.Suggestion;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettleUpSuggestionsTests {

    private static final Currency EUR = Currency.getInstance("EUR");

    private static final UUID A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-7000-8000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-7000-8000-00000000000d");
    private static final UUID E = UUID.fromString("00000000-0000-7000-8000-00000000000e");

    /** ADR-0012's example: four payments, where three would do. */
    @Test
    void theLargestDebtorPaysTheLargestCreditorUntilEveryoneIsSettled() {
        List<Suggestion> suggestions = SettleUpSuggestions.suggest(
                List.of(balance(A, 700), balance(B, 500), balance(C, -500), balance(D, -400), balance(E, -300)));

        assertThat(suggestions)
                .containsExactly(
                        new Suggestion(C, A, eur(500)),
                        new Suggestion(D, B, eur(400)),
                        new Suggestion(E, A, eur(200)),
                        new Suggestion(E, B, eur(100)));
    }

    @Test
    void tiesGoToTheMemberIdThatComesFirstAsText() {
        // 8… sorts before 0… with UUID.compareTo, which compares signed numbers; as text 0… comes first.
        UUID first = UUID.fromString("0fffffff-0000-7000-8000-000000000000");
        UUID second = UUID.fromString("8fffffff-0000-7000-8000-000000000000");

        assertThat(SettleUpSuggestions.suggest(List.of(balance(second, -100), balance(first, -100), balance(A, 200))))
                .containsExactly(new Suggestion(first, A, eur(100)), new Suggestion(second, A, eur(100)));
        assertThat(SettleUpSuggestions.suggest(List.of(balance(second, 100), balance(first, 100), balance(A, -200))))
                .containsExactly(new Suggestion(A, first, eur(100)), new Suggestion(A, second, eur(100)));
    }

    @Test
    void aSettledGroupGetsNoSuggestions() {
        assertThat(SettleUpSuggestions.suggest(List.of(balance(A, 0), balance(B, 0))))
                .isEmpty();
        assertThat(SettleUpSuggestions.suggest(List.of())).isEmpty();
    }

    @Test
    void refusesBalancesNoGroupCanHave() {
        assertThatThrownBy(() -> SettleUpSuggestions.suggest(List.of(balance(A, 100), balance(B, -99))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("add up to 1");
        assertThatThrownBy(() -> SettleUpSuggestions.suggest(List.of(balance(A, 100), balance(A, -100))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettleUpSuggestions.suggest(
                        List.of(balance(A, 100), new MemberBalance(B, new Money(-100, Currency.getInstance("USD"))))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MemberBalance balance(UUID member, long minor) {
        return new MemberBalance(member, eur(minor));
    }

    private static Money eur(long minor) {
        return new Money(minor, EUR);
    }
}
