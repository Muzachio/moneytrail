package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.identity.SignedIn;
import io.github.muzachio.moneytrail.shared.Money;
import io.github.muzachio.moneytrail.shared.api.TransactionsApi;
import io.github.muzachio.moneytrail.shared.api.model.NewTransaction;
import io.github.muzachio.moneytrail.shared.api.model.PositiveMoney;
import io.github.muzachio.moneytrail.shared.api.model.TrailAction;
import io.github.muzachio.moneytrail.shared.api.model.TrailStep;
import io.github.muzachio.moneytrail.shared.api.model.Transaction;
import io.github.muzachio.moneytrail.shared.api.model.TransactionDetail;
import io.github.muzachio.moneytrail.shared.api.model.TransactionReversal;
import io.github.muzachio.moneytrail.shared.api.model.TransactionType;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Both {@code POST}s take the {@code Idempotency-Key} header (api/openapi.yaml), so
 * shared.idempotency runs each of them once per key, in one transaction with the stored response.
 */
@RestController
class TransactionsController implements TransactionsApi {

    private final Transactions transactions;

    TransactionsController(Transactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public ResponseEntity<Transaction> createTransaction(String idempotencyKey, NewTransaction newTransaction) {
        UserTransaction recorded = transactions.record(SignedIn.userId(), toMovement(newTransaction));
        return ResponseEntity.status(HttpStatus.CREATED).body(toApi(recorded));
    }

    @Override
    public ResponseEntity<TransactionDetail> getTransaction(UUID id) {
        return ResponseEntity.ok(toApi(transactions.find(SignedIn.userId(), id)));
    }

    @Override
    public ResponseEntity<TransactionDetail> reverseTransaction(
            String idempotencyKey, UUID id, @Nullable TransactionReversal transactionReversal) {
        NewTransaction replacement = transactionReversal == null ? null : transactionReversal.getReplacement();
        Movement correction = replacement == null ? null : toMovement(replacement);
        return ResponseEntity.ok(toApi(transactions.reverse(SignedIn.userId(), id, correction)));
    }

    /**
     * The schema cannot say which properties each type takes, so this does; a property of another
     * type is refused rather than ignored, as it most likely means the client sent the wrong type.
     */
    private static Movement toMovement(NewTransaction body) {
        Money amount = toMoney(body.getAmount());
        UUID categoryId = body.getCategoryId();
        UUID accountId = body.getAccountId();
        UUID fromAccountId = body.getFromAccountId();
        UUID toAccountId = body.getToAccountId();
        return switch (body.getType()) {
            case EXPENSE, INCOME -> {
                if (fromAccountId != null || toAccountId != null) {
                    throw TransactionException.badRequest(
                            "An expense or an income takes categoryId and accountId, not fromAccountId or toAccountId.");
                }
                if (categoryId == null || accountId == null) {
                    throw TransactionException.badRequest(
                            "An expense or an income needs a categoryId and an accountId.");
                }
                yield body.getType() == TransactionType.EXPENSE
                        ? new Movement.Expense(
                                categoryId, accountId, amount, body.getOccurredOn(), body.getDescription())
                        : new Movement.Income(
                                categoryId, accountId, amount, body.getOccurredOn(), body.getDescription());
            }
            case TRANSFER -> {
                if (categoryId != null || accountId != null) {
                    throw TransactionException.badRequest(
                            "A transfer takes fromAccountId and toAccountId, not categoryId or accountId.");
                }
                if (fromAccountId == null || toAccountId == null) {
                    throw TransactionException.badRequest("A transfer needs a fromAccountId and a toAccountId.");
                }
                yield new Movement.Transfer(
                        fromAccountId, toAccountId, amount, body.getOccurredOn(), body.getDescription());
            }
        };
    }

    /**
     * The schema only checks for three capital letters, and {@link Currency} refuses a code it does
     * not know: such a code is not the accounts' currency either.
     */
    private static Money toMoney(PositiveMoney amount) {
        try {
            return new Money(amount.getAmountMinor(), Currency.getInstance(amount.getCurrency()));
        } catch (IllegalArgumentException unknownCurrency) {
            throw TransactionException.currencyMismatch(amount.getCurrency());
        }
    }

    private static TransactionDetail toApi(TransactionHistory history) {
        TransactionDetail body = new TransactionDetail(
                toApi(history.transaction()),
                history.postings().stream()
                        .map(posting -> new io.github.muzachio.moneytrail.shared.api.model.Posting(
                                posting.accountId(), toApi(posting.amount())))
                        .toList(),
                history.trail().stream().map(TransactionsController::toApi).toList());
        UUID currentId = history.currentId();
        if (currentId != null) {
            body.currentId(currentId);
        }
        return body;
    }

    private static TrailStep toApi(TransactionHistory.Step step) {
        TrailStep body = new TrailStep(
                step.entryId(),
                switch (step.action()) {
                    case RECORDED -> TrailAction.RECORDED;
                    case REVERSED -> TrailAction.REVERSED;
                    case CORRECTED -> TrailAction.CORRECTED;
                },
                toApi(step.type()),
                toApi(step.amount()),
                step.recordedAt().atOffset(ZoneOffset.UTC));
        UUID reversesEntryId = step.reversesEntryId();
        if (reversesEntryId != null) {
            body.reversesEntryId(reversesEntryId);
        }
        return body;
    }

    private static Transaction toApi(UserTransaction transaction) {
        Transaction body = new Transaction(
                transaction.id(),
                toApi(transaction.type()),
                toApi(transaction.amount()),
                transaction.occurredOn(),
                transaction.description(),
                transaction.recordedAt().atOffset(ZoneOffset.UTC),
                transaction.edited(),
                transaction.reversed());
        UUID categoryId = transaction.categoryId();
        UUID accountId = transaction.accountId();
        UUID fromAccountId = transaction.fromAccountId();
        UUID toAccountId = transaction.toAccountId();
        if (categoryId != null) {
            body.categoryId(categoryId);
        }
        if (accountId != null) {
            body.accountId(accountId);
        }
        if (fromAccountId != null) {
            body.fromAccountId(fromAccountId);
        }
        if (toAccountId != null) {
            body.toAccountId(toAccountId);
        }
        return body;
    }

    private static TransactionType toApi(UserTransaction.Type type) {
        return switch (type) {
            case EXPENSE -> TransactionType.EXPENSE;
            case INCOME -> TransactionType.INCOME;
            case TRANSFER -> TransactionType.TRANSFER;
        };
    }

    private static io.github.muzachio.moneytrail.shared.api.model.Money toApi(Money money) {
        return new io.github.muzachio.moneytrail.shared.api.model.Money(
                money.minor(), money.currency().getCurrencyCode());
    }
}
