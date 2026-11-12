package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.identity.SignedIn;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.shared.api.CategoriesApi;
import io.github.muzachio.moneytrail.shared.api.model.Category;
import io.github.muzachio.moneytrail.shared.api.model.CategoryKind;
import io.github.muzachio.moneytrail.shared.api.model.CategoryList;
import io.github.muzachio.moneytrail.shared.api.model.CategoryUpdate;
import io.github.muzachio.moneytrail.shared.api.model.NewCategory;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CategoriesController implements CategoriesApi {

    private final Categories categories;

    CategoriesController(Categories categories) {
        this.categories = categories;
    }

    @Override
    public ResponseEntity<CategoryList> listCategories() {
        return ResponseEntity.ok(new CategoryList(categories.list(SignedIn.userId()).stream()
                .map(CategoriesController::toApi)
                .toList()));
    }

    @Override
    public ResponseEntity<Category> createCategory(NewCategory newCategory) {
        UserCategory created =
                categories.create(SignedIn.userId(), toAccountKind(newCategory.getKind()), newCategory.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(toApi(created));
    }

    @Override
    public ResponseEntity<Category> updateCategory(UUID id, CategoryUpdate update) {
        return ResponseEntity.ok(
                toApi(categories.update(SignedIn.userId(), id, update.getName(), update.getArchived())));
    }

    private static Category toApi(UserCategory category) {
        Category body = new Category(category.id(), toCategoryKind(category.kind()), category.archived());
        if (category.key() != null) {
            body.key(category.key());
        }
        if (category.name() != null) {
            body.name(category.name());
        }
        return body;
    }

    private static AccountKind toAccountKind(CategoryKind kind) {
        return switch (kind) {
            case EXPENSE -> AccountKind.EXPENSE;
            case INCOME -> AccountKind.INCOME;
        };
    }

    private static CategoryKind toCategoryKind(AccountKind kind) {
        return switch (kind) {
            case EXPENSE -> CategoryKind.EXPENSE;
            case INCOME -> CategoryKind.INCOME;
            default -> throw new IllegalArgumentException(kind + " is not a category kind");
        };
    }
}
