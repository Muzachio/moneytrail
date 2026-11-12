package io.github.muzachio.moneytrail.personal.internal;

import static java.util.Comparator.comparingInt;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user's categories: the {@code EXPENSE} and {@code INCOME} accounts of their personal ledger.
 * Name rules (unique per kind among active categories, ignoring case) are the ledger's, which
 * answers {@code category_name_taken}.
 */
@Service
@Transactional
class Categories {

    // Expenses before income; within a kind the defaults in their order, then the user's own. The
    // sort is stable, so the user's own stay in the ledger's order, which is the order of creation.
    private static final Comparator<Account> LIST_ORDER = comparingInt(
                    (Account account) -> account.kind() == AccountKind.EXPENSE ? 0 : 1)
            .thenComparingInt(account -> DefaultCategory.of(account.kind(), account.subtype())
                    .map(Enum::ordinal)
                    .orElse(Integer.MAX_VALUE));

    private final LedgerService ledger;
    private final PersonalLedgers personalLedgers;

    Categories(LedgerService ledger, PersonalLedgers personalLedgers) {
        this.ledger = ledger;
        this.personalLedgers = personalLedgers;
    }

    List<UserCategory> list(UUID userId) {
        return ledger.accounts(personalLedgers.ledgerOf(userId)).stream()
                .filter(Categories::isCategory)
                .sorted(LIST_ORDER)
                .map(UserCategory::of)
                .toList();
    }

    UserCategory create(UUID userId, AccountKind kind, String name) {
        if (!isCategoryKind(kind)) {
            throw new IllegalArgumentException(kind + " is not a category kind");
        }
        UUID ledgerId = personalLedgers.ledgerOf(userId);
        return UserCategory.of(
                ledger.openAccount(new NewAccount(ledgerId, kind, null, clean(name), PersonalLedgers.CURRENCY)));
    }

    /**
     * Archives before renaming and restores after, so a name is only checked against the other
     * active categories when the category itself ends up active.
     */
    UserCategory update(UUID userId, UUID categoryId, @Nullable String name, @Nullable Boolean archived) {
        if (name == null && archived == null) {
            throw CategoryException.badRequest("Send a name, archived, or both.");
        }
        UUID ledgerId = personalLedgers.ledgerOf(userId);
        Account category = ledger.findAccount(categoryId)
                .filter(account -> account.ledgerId().equals(ledgerId) && isCategory(account))
                .orElseThrow(() -> CategoryException.notFound(categoryId));
        if (Boolean.TRUE.equals(archived)) {
            category = ledger.archiveAccount(categoryId);
        }
        if (name != null) {
            category = ledger.renameAccount(categoryId, clean(name));
        }
        if (Boolean.FALSE.equals(archived)) {
            category = ledger.restoreAccount(categoryId);
        }
        return UserCategory.of(category);
    }

    // The schema refuses blank names, but its pattern only knows ASCII spaces; strip() knows all.
    private static String clean(String name) {
        String stripped = name.strip();
        if (stripped.isEmpty()) {
            throw CategoryException.badRequest("A category needs a name.");
        }
        return stripped;
    }

    private static boolean isCategory(Account account) {
        return isCategoryKind(account.kind());
    }

    private static boolean isCategoryKind(AccountKind kind) {
        return kind == AccountKind.EXPENSE || kind == AccountKind.INCOME;
    }
}
