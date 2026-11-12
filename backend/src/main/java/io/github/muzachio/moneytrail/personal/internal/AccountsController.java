package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.identity.SignedIn;
import io.github.muzachio.moneytrail.shared.Money;
import io.github.muzachio.moneytrail.shared.api.AccountsApi;
import io.github.muzachio.moneytrail.shared.api.model.Account;
import io.github.muzachio.moneytrail.shared.api.model.AccountList;
import io.github.muzachio.moneytrail.shared.api.model.AccountType;
import io.github.muzachio.moneytrail.shared.api.model.AccountUpdate;
import io.github.muzachio.moneytrail.shared.api.model.CurrencyCode;
import io.github.muzachio.moneytrail.shared.api.model.NewAccount;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST} takes the {@code Idempotency-Key} header (api/openapi.yaml), so shared.idempotency
 * runs it once per key, in one transaction with the stored response.
 */
@RestController
class AccountsController implements AccountsApi {

    private final UserAccounts accounts;

    AccountsController(UserAccounts accounts) {
        this.accounts = accounts;
    }

    @Override
    public ResponseEntity<AccountList> listAccounts() {
        return ResponseEntity.ok(new AccountList(accounts.list(SignedIn.userId()).stream()
                .map(AccountsController::toApi)
                .toList()));
    }

    @Override
    public ResponseEntity<Account> createAccount(String idempotencyKey, NewAccount newAccount) {
        Currency currency = Currency.getInstance(newAccount.getCurrency().getValue());
        UserAccount created = accounts.create(
                SignedIn.userId(),
                toType(newAccount.getType()),
                newAccount.getName(),
                currency,
                openingBalance(newAccount, currency));
        return ResponseEntity.status(HttpStatus.CREATED).body(toApi(created));
    }

    @Override
    public ResponseEntity<Account> updateAccount(UUID id, AccountUpdate update) {
        return ResponseEntity.ok(toApi(accounts.update(SignedIn.userId(), id, update.getName(), update.getArchived())));
    }

    /**
     * Compares the codes before making a {@link Currency} of the balance's, which the schema only
     * checks for three capital letters: an unknown code would not be a currency at all.
     */
    private static @Nullable Money openingBalance(NewAccount newAccount, Currency currency) {
        io.github.muzachio.moneytrail.shared.api.model.Money balance = newAccount.getOpeningBalance();
        if (balance == null) {
            return null;
        }
        if (!balance.getCurrency().equals(currency.getCurrencyCode())) {
            throw AccountException.badRequest("The opening balance must be in the account's currency.");
        }
        return new Money(balance.getAmountMinor(), currency);
    }

    private static Account toApi(UserAccount account) {
        Account body = new Account(
                account.id(),
                account.name(),
                toApi(account.type()),
                CurrencyCode.fromValue(account.currency().getCurrencyCode()),
                toApi(account.balance()),
                toApi(account.changeThisMonth()),
                account.archived());
        Instant archivedAt = account.archivedAt();
        if (archivedAt != null) {
            body.archivedAt(archivedAt.atOffset(ZoneOffset.UTC));
        }
        return body;
    }

    private static io.github.muzachio.moneytrail.shared.api.model.Money toApi(Money money) {
        return new io.github.muzachio.moneytrail.shared.api.model.Money(
                money.minor(), money.currency().getCurrencyCode());
    }

    private static UserAccount.Type toType(AccountType type) {
        return switch (type) {
            case CHECKING -> UserAccount.Type.CHECKING;
            case SAVINGS -> UserAccount.Type.SAVINGS;
            case CASH -> UserAccount.Type.CASH;
            case CREDIT_CARD -> UserAccount.Type.CREDIT_CARD;
        };
    }

    private static AccountType toApi(UserAccount.Type type) {
        return switch (type) {
            case CHECKING -> AccountType.CHECKING;
            case SAVINGS -> AccountType.SAVINGS;
            case CASH -> AccountType.CASH;
            case CREDIT_CARD -> AccountType.CREDIT_CARD;
        };
    }
}
