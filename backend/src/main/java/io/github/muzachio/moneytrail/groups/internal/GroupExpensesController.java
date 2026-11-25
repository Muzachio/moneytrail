package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.groups.internal.Split.MemberShare;
import io.github.muzachio.moneytrail.identity.SignedIn;
import io.github.muzachio.moneytrail.shared.Money;
import io.github.muzachio.moneytrail.shared.api.GroupExpensesApi;
import io.github.muzachio.moneytrail.shared.api.model.ExpenseShare;
import io.github.muzachio.moneytrail.shared.api.model.ExpenseSplit;
import io.github.muzachio.moneytrail.shared.api.model.GroupExpense;
import io.github.muzachio.moneytrail.shared.api.model.GroupExpenseDetail;
import io.github.muzachio.moneytrail.shared.api.model.GroupExpensePage;
import io.github.muzachio.moneytrail.shared.api.model.GroupExpenseReversal;
import io.github.muzachio.moneytrail.shared.api.model.GroupExpenseTrailStep;
import io.github.muzachio.moneytrail.shared.api.model.NewGroupExpense;
import io.github.muzachio.moneytrail.shared.api.model.NewSplitMember;
import io.github.muzachio.moneytrail.shared.api.model.PositiveMoney;
import io.github.muzachio.moneytrail.shared.api.model.TrailAction;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
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
class GroupExpensesController implements GroupExpensesApi {

    private final GroupExpenses expenses;

    GroupExpensesController(GroupExpenses expenses) {
        this.expenses = expenses;
    }

    @Override
    public ResponseEntity<GroupExpense> createGroupExpense(
            String idempotencyKey, UUID id, NewGroupExpense newGroupExpense) {
        Expense recorded = expenses.record(SignedIn.userId(), id, toExpense(newGroupExpense));
        return ResponseEntity.status(HttpStatus.CREATED).body(toApi(recorded));
    }

    /** The schema has already checked the cursor's length and the limit's range. */
    @Override
    public ResponseEntity<GroupExpensePage> listGroupExpenses(UUID id, @Nullable String cursor, Integer limit) {
        ExpensePage page = expenses.list(SignedIn.userId(), id, cursor, limit);
        GroupExpensePage body = new GroupExpensePage(
                page.items().stream().map(GroupExpensesController::toApi).toList());
        String nextCursor = page.nextCursor();
        if (nextCursor != null) {
            body.nextCursor(nextCursor);
        }
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<GroupExpenseDetail> getGroupExpense(UUID id, UUID expenseId) {
        return ResponseEntity.ok(toApi(expenses.find(SignedIn.userId(), id, expenseId)));
    }

    @Override
    public ResponseEntity<GroupExpenseDetail> reverseGroupExpense(
            String idempotencyKey, UUID id, UUID expenseId, @Nullable GroupExpenseReversal groupExpenseReversal) {
        NewGroupExpense replacement = groupExpenseReversal == null ? null : groupExpenseReversal.getReplacement();
        NewExpense correction = replacement == null ? null : toExpense(replacement);
        return ResponseEntity.ok(toApi(expenses.reverse(SignedIn.userId(), id, expenseId, correction)));
    }

    /**
     * The schema cannot say that {@code value} goes with every method but one, so this does. A value
     * sent with an equal split is refused rather than ignored: it most likely means the client sent
     * the wrong method.
     */
    private static NewExpense toExpense(NewGroupExpense body) {
        SplitMethod method = toMethod(body.getSplit().getMethod());
        List<SplitInput> inputs = body.getSplit().getMembers().stream()
                .map(member -> new SplitInput(member.getUserId(), valueOf(member, method)))
                .toList();
        return new NewExpense(
                toMoney(body.getAmount()),
                body.getOccurredOn(),
                body.getDescription(),
                body.getPaidBy(),
                method,
                inputs);
    }

    private static long valueOf(NewSplitMember member, SplitMethod method) {
        Long value = member.getValue();
        if (method == SplitMethod.EQUALLY) {
            if (value != null) {
                throw GroupExpenseException.badRequest("Leave value out when splitting equally.");
            }
            return 1;
        }
        if (value == null) {
            throw GroupExpenseException.badRequest(
                    "Each member of the split needs a value, except when splitting equally.");
        }
        return value;
    }

    /**
     * The schema only checks for three capital letters, and {@link Currency} refuses a code it does
     * not know: such a code is not the group's currency either.
     */
    private static Money toMoney(PositiveMoney amount) {
        try {
            return new Money(amount.getAmountMinor(), Currency.getInstance(amount.getCurrency()));
        } catch (IllegalArgumentException unknownCurrency) {
            throw GroupExpenseException.currencyMismatch(amount.getCurrency());
        }
    }

    private static SplitMethod toMethod(io.github.muzachio.moneytrail.shared.api.model.SplitMethod method) {
        return switch (method) {
            case EQUALLY -> SplitMethod.EQUALLY;
            case SHARES -> SplitMethod.SHARES;
            case PERCENTAGES -> SplitMethod.PERCENTAGES;
            case EXACT_AMOUNTS -> SplitMethod.EXACT_AMOUNTS;
        };
    }

    private static io.github.muzachio.moneytrail.shared.api.model.SplitMethod toApi(SplitMethod method) {
        return switch (method) {
            case EQUALLY -> io.github.muzachio.moneytrail.shared.api.model.SplitMethod.EQUALLY;
            case SHARES -> io.github.muzachio.moneytrail.shared.api.model.SplitMethod.SHARES;
            case PERCENTAGES -> io.github.muzachio.moneytrail.shared.api.model.SplitMethod.PERCENTAGES;
            case EXACT_AMOUNTS -> io.github.muzachio.moneytrail.shared.api.model.SplitMethod.EXACT_AMOUNTS;
        };
    }

    private static GroupExpenseDetail toApi(ExpenseHistory history) {
        GroupExpenseDetail body = new GroupExpenseDetail(
                toApi(history.expense()),
                history.trail().stream().map(GroupExpensesController::toApi).toList());
        UUID currentId = history.currentId();
        if (currentId != null) {
            body.currentId(currentId);
        }
        return body;
    }

    private static GroupExpenseTrailStep toApi(ExpenseHistory.Step step) {
        GroupExpenseTrailStep body = new GroupExpenseTrailStep(
                step.entryId(),
                switch (step.action()) {
                    case RECORDED -> TrailAction.RECORDED;
                    case REVERSED -> TrailAction.REVERSED;
                    case CORRECTED -> TrailAction.CORRECTED;
                },
                toApi(step.amount()),
                step.recordedBy(),
                utc(step.recordedAt()));
        UUID reversesEntryId = step.reversesEntryId();
        if (reversesEntryId != null) {
            body.reversesEntryId(reversesEntryId);
        }
        return body;
    }

    private static GroupExpense toApi(Expense expense) {
        return new GroupExpense(
                expense.id(),
                toApi(expense.amount()),
                expense.occurredOn(),
                expense.description(),
                expense.paidBy(),
                new ExpenseSplit(
                        toApi(expense.split().method()),
                        expense.split().shares().stream()
                                .map(GroupExpensesController::toApi)
                                .toList()),
                expense.recordedBy(),
                utc(expense.recordedAt()),
                expense.edited(),
                expense.reversed());
    }

    private static ExpenseShare toApi(MemberShare share) {
        return new ExpenseShare(share.memberId(), share.input(), toApi(share.share()));
    }

    private static io.github.muzachio.moneytrail.shared.api.model.Money toApi(Money money) {
        return new io.github.muzachio.moneytrail.shared.api.model.Money(
                money.minor(), money.currency().getCurrencyCode());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
