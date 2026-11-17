package io.github.muzachio.moneytrail.personal.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.muzachio.moneytrail.shared.api.model.CategoryIcon;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class IconTests {

    /** The controller maps one to the other by name, so a name missing on either side would fail there. */
    @Test
    void hasTheNamesOfTheApisListInTheSameOrder() {
        assertThat(Arrays.stream(Icon.values()).map(Icon::value))
                .containsExactlyElementsOf(Arrays.stream(CategoryIcon.values())
                        .map(CategoryIcon::getValue)
                        .toList());
    }

    @Test
    void findsAnIconByItsName() {
        assertThat(Icon.of("paw-print")).isEqualTo(Icon.PAW_PRINT);
        assertThatIllegalArgumentException().isThrownBy(() -> Icon.of("PAW_PRINT"));
    }

    /** Other and other-income share the tag, so twelve categories have eleven icons. */
    @Test
    void theDefaultCategoriesHaveTheFirstElevenIcons() {
        assertThat(Arrays.stream(DefaultCategory.values())
                        .map(DefaultCategory::icon)
                        .distinct())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.asList(Icon.values()).subList(0, 11));
    }
}
