package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.identity.SignedIn;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.shared.api.CategoriesApi;
import io.github.muzachio.moneytrail.shared.api.model.Category;
import io.github.muzachio.moneytrail.shared.api.model.CategoryIcon;
import io.github.muzachio.moneytrail.shared.api.model.CategoryKey;
import io.github.muzachio.moneytrail.shared.api.model.CategoryKind;
import io.github.muzachio.moneytrail.shared.api.model.CategoryList;
import io.github.muzachio.moneytrail.shared.api.model.CategoryUpdate;
import io.github.muzachio.moneytrail.shared.api.model.NewCategory;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
        Naming naming = toNaming(newCategory.getKey(), newCategory.getName());
        if (naming == null) {
            throw CategoryException.badRequest("Send a key or a name.");
        }
        CategoryIcon icon = newCategory.getIcon();
        UserCategory created = categories.create(
                SignedIn.userId(),
                toAccountKind(newCategory.getKind()),
                naming,
                icon == null ? Icon.TAG : toIcon(icon));
        return ResponseEntity.status(HttpStatus.CREATED).body(toApi(created));
    }

    @Override
    public ResponseEntity<Category> updateCategory(UUID id, CategoryUpdate update) {
        CategoryIcon icon = update.getIcon();
        return ResponseEntity.ok(toApi(categories.update(
                SignedIn.userId(),
                id,
                toNaming(update.getKey(), update.getName()),
                icon == null ? null : toIcon(icon),
                update.getArchived())));
    }

    private static Category toApi(UserCategory category) {
        Category body = new Category(
                category.id(),
                toCategoryKind(category.kind()),
                category.isDefault(),
                CategoryIcon.fromValue(category.icon().value()),
                category.archived());
        KnownName key = category.key();
        if (key != null) {
            body.key(CategoryKey.fromValue(key.key()));
        }
        if (category.name() != null) {
            body.name(category.name());
        }
        return body;
    }

    /** Both rename the category, so one of them at most; null when neither was sent. */
    private static @Nullable Naming toNaming(@Nullable CategoryKey key, @Nullable String name) {
        if (key != null && name != null) {
            throw CategoryException.badRequest("Send a key or a name, not both.");
        }
        if (key != null) {
            return new Naming.Known(KnownName.of(key.getValue()).orElseThrow());
        }
        return name == null ? null : new Naming.Typed(name);
    }

    // By the name both lists share, so the API's list and Icon cannot pair the wrong icons.
    private static Icon toIcon(CategoryIcon icon) {
        return Icon.of(icon.getValue());
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
