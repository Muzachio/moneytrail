package io.github.muzachio.moneytrail.personal.internal;

import static java.util.Comparator.comparingInt;
import static java.util.Comparator.naturalOrder;
import static java.util.Comparator.nullsLast;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user's categories: the {@code EXPENSE} and {@code INCOME} accounts of their personal ledger,
 * with the known names and the icons this module keeps for them. Name rules (unique per kind among
 * active categories, ignoring case) are the ledger's, which answers {@code category_name_taken}; a
 * known name is stored in English, so the rule sees it in every language.
 */
@Service
@Transactional
class Categories {

    // Expenses before income; within a kind the defaults in their order (an enum's natural order),
    // whatever they are called, then the user's own. The sort is stable, so the user's own stay in the
    // ledger's order, which is the order of creation.
    private static final Comparator<UserCategory> LIST_ORDER = comparingInt(
                    (UserCategory category) -> category.kind() == AccountKind.EXPENSE ? 0 : 1)
            .thenComparing(UserCategory::defaultCategory, nullsLast(naturalOrder()));

    private final LedgerService ledger;
    private final PersonalLedgers personalLedgers;
    private final CategoryKeys keys;
    private final CategoryIcons icons;

    Categories(LedgerService ledger, PersonalLedgers personalLedgers, CategoryKeys keys, CategoryIcons icons) {
        this.ledger = ledger;
        this.personalLedgers = personalLedgers;
        this.keys = keys;
        this.icons = icons;
    }

    List<UserCategory> list(UUID userId) {
        return inLedger(personalLedgers.ledgerOf(userId)).stream()
                .sorted(LIST_ORDER)
                .toList();
    }

    /**
     * Every category of a ledger, archived ones included, in the ledger's order. One query finds the
     * known names of them all, another the icons of the user's own; the defaults' icons are fixed.
     */
    List<UserCategory> inLedger(UUID ledgerId) {
        List<Account> categories = ledger.accounts(ledgerId).stream()
                .filter(Categories::isCategory)
                .toList();
        Map<UUID, KnownName> known =
                keys.of(categories.stream().map(Account::id).toList());
        Map<UUID, Icon> picked = icons.of(categories.stream()
                .filter(category -> !isDefault(category))
                .map(Account::id)
                .toList());
        return categories.stream()
                .map(category -> UserCategory.of(category, known, picked))
                .toList();
    }

    UserCategory create(UUID userId, AccountKind kind, Naming naming, Icon icon) {
        if (!isCategoryKind(kind)) {
            throw new IllegalArgumentException(kind + " is not a category kind");
        }
        checkKind(naming, kind);
        UUID ledgerId = personalLedgers.ledgerOf(userId);
        Account created =
                ledger.openAccount(new NewAccount(ledgerId, kind, null, naming.stored(), PersonalLedgers.CURRENCY));
        callBy(created.id(), naming);
        icons.pick(created.id(), icon);
        return read(created);
    }

    /**
     * Archives before renaming and restores after, so a name is only checked against the other
     * active categories when the category itself ends up active. A refused name rolls the icon back
     * with the rest.
     */
    UserCategory update(
            UUID userId, UUID categoryId, @Nullable Naming naming, @Nullable Icon icon, @Nullable Boolean archived) {
        if (naming == null && icon == null && archived == null) {
            throw CategoryException.badRequest("Send a key or a name, an icon, archived, or more than one of them.");
        }
        UUID ledgerId = personalLedgers.ledgerOf(userId);
        Account category = ledger.findAccount(categoryId)
                .filter(account -> account.ledgerId().equals(ledgerId) && isCategory(account))
                .orElseThrow(() -> CategoryException.notFound(categoryId));
        if (naming != null) {
            checkKind(naming, category.kind());
        }
        if (icon != null && isDefault(category)) {
            throw CategoryException.badRequest("A default category keeps its icon.");
        }
        if (Boolean.TRUE.equals(archived)) {
            category = ledger.archiveAccount(categoryId);
        }
        if (naming != null) {
            category = ledger.renameAccount(categoryId, naming.stored());
            callBy(categoryId, naming);
        }
        if (icon != null) {
            icons.pick(categoryId, icon);
        }
        if (Boolean.FALSE.equals(archived)) {
            category = ledger.restoreAccount(categoryId);
        }
        return read(category);
    }

    private UserCategory read(Account category) {
        List<UUID> id = List.of(category.id());
        return UserCategory.of(category, keys.of(id), isDefault(category) ? Map.of() : icons.of(id));
    }

    /** A known name is kept as its key; a typed one has none, so the account's name is shown. */
    private void callBy(UUID categoryId, Naming naming) {
        if (naming instanceof Naming.Known known) {
            keys.give(categoryId, known.name());
        } else {
            keys.remove(categoryId);
        }
    }

    private static void checkKind(Naming naming, AccountKind kind) {
        if (naming instanceof Naming.Known known && known.name().kind() != kind) {
            throw CategoryException.badRequest("%s is not a name for an %s category."
                    .formatted(known.name().key(), kind.name().toLowerCase(Locale.ROOT)));
        }
    }

    private static boolean isDefault(Account category) {
        return DefaultCategory.of(category.kind(), category.subtype()).isPresent();
    }

    private static boolean isCategory(Account account) {
        return isCategoryKind(account.kind());
    }

    private static boolean isCategoryKind(AccountKind kind) {
        return kind == AccountKind.EXPENSE || kind == AccountKind.INCOME;
    }
}
