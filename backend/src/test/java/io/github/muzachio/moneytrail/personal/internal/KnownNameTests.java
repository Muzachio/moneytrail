package io.github.muzachio.moneytrail.personal.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.shared.api.model.CategoryKey;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class KnownNameTests {

    /** The controller maps one to the other by key, so a key missing on either side would fail there. */
    @Test
    void hasTheKeysOfTheApisListInTheSameOrder() {
        assertThat(Arrays.stream(KnownName.values()).map(KnownName::key))
                .containsExactlyElementsOf(Arrays.stream(CategoryKey.values())
                        .map(CategoryKey::getValue)
                        .toList());
    }

    @Test
    void theDefaultCategoriesHaveTheFirstTwelveNamesInTheirOrder() {
        assertThat(Arrays.stream(DefaultCategory.values()).map(DefaultCategory::knownName))
                .containsExactlyElementsOf(Arrays.asList(KnownName.values()).subList(0, 12));
    }

    /** Two known names that stored the same English name would take each other's place. */
    @Test
    void noTwoNamesOfAKindShareTheirEnglishName() {
        for (AccountKind kind : new AccountKind[] {AccountKind.EXPENSE, AccountKind.INCOME}) {
            assertThat(Arrays.stream(KnownName.values())
                            .filter(name -> name.kind() == kind)
                            .map(name -> name.english().toLowerCase(Locale.ROOT)))
                    .as(kind.name())
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    void findsANameByItsKey() {
        assertThat(KnownName.of("gifts-given")).contains(KnownName.GIFTS_GIVEN);
        assertThat(KnownName.of("GYM")).isEmpty();
    }
}
