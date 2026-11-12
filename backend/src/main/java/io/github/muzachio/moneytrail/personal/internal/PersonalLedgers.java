package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.identity.UserSignedIn;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.Ledger;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each user's personal ledger, opened with the default categories and an {@code Equity:Opening
 * balances} account the first time they sign in.
 */
@Service
@Transactional
class PersonalLedgers {

    /** The only currency until multi-currency accounts (M4). */
    static final Currency CURRENCY = Currency.getInstance("EUR");

    /** The subtype of the account that opening balances are posted against (#44). */
    static final String OPENING_BALANCES = "opening-balances";

    private final LedgerService ledger;

    PersonalLedgers(LedgerService ledger) {
        this.ledger = ledger;
    }

    /**
     * Synchronous and in the sign-in's transaction (MANDATORY refuses to run without one), not an
     * {@code @ApplicationModuleListener} after commit: the app asks for the categories right after
     * sign-in, and a user must never exist without them. If this fails, the sign-in fails and can
     * simply be retried. Runs on every sign-in, so users from before this listener get their ledger
     * on their next one.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void onSignIn(UserSignedIn event) {
        ledgerOf(event.userId());
    }

    /**
     * The id of the user's personal ledger, opened now if they have none yet. The categories
     * endpoints call it too, so a session that began before the ledgers existed still finds its
     * categories.
     */
    UUID ledgerOf(UUID userId) {
        LedgerOwner owner = LedgerOwner.user(userId);
        return ledger.findLedger(owner).or(() -> open(owner)).orElseThrow().id();
    }

    private Optional<Ledger> open(LedgerOwner owner) {
        Optional<Ledger> opened = ledger.openLedgerIfAbsent(owner);
        if (opened.isPresent()) {
            openDefaultAccounts(opened.get().id());
            return opened;
        }
        // Another transaction opened it while this one looked, and has committed it together with
        // its accounts: openLedgerIfAbsent waited for that. Each statement reads the latest committed
        // data (READ COMMITTED, Postgres' default), so this one finds it.
        return ledger.findLedger(owner);
    }

    private void openDefaultAccounts(UUID ledgerId) {
        for (DefaultCategory category : DefaultCategory.values()) {
            ledger.openAccount(
                    new NewAccount(ledgerId, category.kind(), category.key(), category.defaultName(), CURRENCY));
        }
        ledger.openAccount(
                new NewAccount(ledgerId, AccountKind.EQUITY, OPENING_BALANCES, "Opening balances", CURRENCY));
    }
}
