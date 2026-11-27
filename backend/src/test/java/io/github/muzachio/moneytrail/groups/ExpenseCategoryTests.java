package io.github.muzachio.moneytrail.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.muzachio.moneytrail.shared.api.model.CategoryKey;
import io.github.muzachio.moneytrail.shared.api.model.GroupExpenseCategory;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The eight keys are written out three times (ExpenseCategory, api/openapi.yaml, V18's check): these
 * keep the first two equal, and every one of them a key the app already shows by name. V18's check
 * is covered by GroupExpensesApiTests, which records an expense in each category.
 */
class ExpenseCategoryTests {

    @Test
    void hasTheSpecsKeysInTheSameOrder() {
        assertThat(Arrays.stream(ExpenseCategory.values()).map(ExpenseCategory::key))
                .containsExactly(Arrays.stream(GroupExpenseCategory.values())
                        .map(GroupExpenseCategory::getValue)
                        .toArray(String[]::new));
    }

    /** The first eight category keys are the default expense categories', which the app translates. */
    @Test
    void areTheDefaultExpenseCategoriesKeys() {
        assertThat(Arrays.stream(CategoryKey.values()).limit(8).map(CategoryKey::getValue))
                .containsExactly(Arrays.stream(ExpenseCategory.values())
                        .map(ExpenseCategory::key)
                        .toArray(String[]::new));
    }

    @Test
    void readsAKeyBack() {
        for (ExpenseCategory category : ExpenseCategory.values()) {
            assertThat(ExpenseCategory.ofKey(category.key())).isEqualTo(category);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> ExpenseCategory.ofKey("travel"));
    }
}
