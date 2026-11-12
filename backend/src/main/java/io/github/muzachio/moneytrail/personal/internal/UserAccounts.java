package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user's own accounts: the checking, savings, cash and credit card accounts of their personal
 * ledger (see {@link UserAccount.Type}). Not called {@code Accounts}, which is the ledger's
 * repository bean.
 */
@Service
@Transactional
class UserAccounts {

    /** The description of an opening balance's journal entry. */
    static final String OPENING_BALANCE = "Opening balance";

    private final LedgerService ledger;
    private final PersonalLedgers personalLedgers;

    UserAccounts(LedgerService ledger, PersonalLedgers personalLedgers) {
        this.ledger = ledger;
        this.personalLedgers = personalLedgers;
    }

    /** In the order they were opened, archived ones included. */
    List<UserAccount> list(UUID userId) {
        UUID ledgerId = personalLedgers.ledgerOf(userId);
        Map<UUID, Money> balances = ledger.balances(ledgerId);
        Map<UUID, Money> changes = changesThisMonth(ledgerId);
        return ledger.accounts(ledgerId).stream()
                .flatMap(account ->
                        UserAccount.Type.of(account).stream().map(type -> view(account, type, balances, changes)))
                .toList();
    }

    /**
     * Opens the account and posts its opening balance in the same transaction, so there is never an
     * account without the balance the user gave it. Under {@code Idempotency-Key} that is also the
     * transaction that stores the response, so a retry finds both or neither.
     */
    UserAccount create(
            UUID userId, UserAccount.Type type, String name, Currency currency, @Nullable Money openingBalance) {
        UUID ledgerId = personalLedgers.ledgerOf(userId);
        Account account = ledger.openAccount(new NewAccount(ledgerId, type.kind(), type.key(), clean(name), currency));
        if (openingBalance != null && !openingBalance.isZero()) {
            postOpeningBalance(userId, account, openingBalance);
        }
        return view(account, type);
    }

    UserAccount update(UUID userId, UUID accountId, @Nullable String name, @Nullable Boolean archived) {
        if (name == null && archived == null) {
            throw AccountException.badRequest("Send a name, archived, or both.");
        }
        UUID ledgerId = personalLedgers.ledgerOf(userId);
        Account account = ledger.findAccount(accountId)
                .filter(found -> found.ledgerId().equals(ledgerId))
                .orElseThrow(() -> AccountException.notFound(accountId));
        UserAccount.Type type = UserAccount.Type.of(account).orElseThrow(() -> AccountException.notFound(accountId));
        if (name != null) {
            account = ledger.renameAccount(accountId, clean(name));
        }
        if (archived != null) {
            account = archived ? ledger.archiveAccount(accountId) : ledger.restoreAccount(accountId);
        }
        return view(account, type);
    }

    /**
     * The account gets the balance and {@code Equity:Opening balances} the opposite, so the entry sums
     * to zero whatever the sign: a card's negative balance (money owed) credits the card and debits
     * equity.
     */
    private void postOpeningBalance(UUID userId, Account account, Money balance) {
        UUID openingBalances = personalLedgers.openingBalancesAccount(account.ledgerId(), account.currency());
        ledger.post(new NewJournalEntry(
                account.ledgerId(),
                today(),
                OPENING_BALANCE,
                userId,
                List.of(new Posting(account.id(), balance), new Posting(openingBalances, balance.negate()))));
    }

    private UserAccount view(Account account, UserAccount.Type type) {
        return view(
                account,
                type,
                Map.of(account.id(), ledger.balance(account.id())),
                changesThisMonth(account.ledgerId()));
    }

    private static UserAccount view(
            Account account, UserAccount.Type type, Map<UUID, Money> balances, Map<UUID, Money> changes) {
        Money zero = Money.zero(account.currency());
        return new UserAccount(
                account.id(),
                type,
                account.name(),
                account.currency(),
                balances.getOrDefault(account.id(), zero),
                changes.getOrDefault(account.id(), zero),
                account.archivedAt());
    }

    private Map<UUID, Money> changesThisMonth(UUID ledgerId) {
        YearMonth month = YearMonth.from(today());
        return ledger.changes(ledgerId, month.atDay(1), month.atEndOfMonth());
    }

    private static LocalDate today() {
        return LocalDate.now(PersonalLedgers.TIME_ZONE);
    }

    // The schema refuses blank names, but its pattern only knows ASCII spaces; strip() knows all.
    private static String clean(String name) {
        String stripped = name.strip();
        if (stripped.isEmpty()) {
            throw AccountException.badRequest("An account needs a name.");
        }
        return stripped;
    }
}
