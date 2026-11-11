package io.github.muzachio.moneytrail.shared.idempotency;

import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.DomainException;
import io.github.muzachio.moneytrail.shared.Money;
import java.net.URI;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Money-moving endpoints for tests only, until the first real one exists (#44, #46). They take the
 * header the way a generated interface does and record real journal entries, so a test can count
 * what was recorded. A test can make the next request fail, or pause it, once it has posted.
 * {@code @TestComponent} keeps it out of component scanning: only tests that import it have it.
 */
@RestController
@TestComponent
class IdempotencyTestEndpoint {

    static final String EXPENSES = "/test/idempotency/expenses";
    static final String INCOMES = "/test/idempotency/incomes";
    static final String PINGS = "/test/idempotency/pings";

    private static final Currency EUR = Currency.getInstance("EUR");

    /** Money moved between a category and an account of the signed-in user's ledger. */
    record Movement(UUID ledgerId, UUID accountId, UUID categoryId, long amountMinor) {}

    record Recorded(UUID entryId, long amountMinor) {}

    static final class SimulatedFailure extends DomainException {

        SimulatedFailure() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "simulated_failure", "The test made this request fail.");
        }
    }

    private final LedgerService ledger;
    private final AtomicReference<Runnable> afterNextPosting = new AtomicReference<>(IdempotencyTestEndpoint::nothing);
    private final AtomicInteger pings = new AtomicInteger();

    IdempotencyTestEndpoint(LedgerService ledger) {
        this.ledger = ledger;
    }

    /** Runs inside the next request, after its journal entry was posted and before it returns. */
    void afterNextPosting(Runnable action) {
        afterNextPosting.set(action);
    }

    private static void nothing() {}

    int pings() {
        return pings.get();
    }

    @PostMapping(
            path = EXPENSES,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Recorded> recordExpense(
            @RequestHeader(IdempotentEndpoints.HEADER) String idempotencyKey,
            @RequestBody Movement movement,
            Principal principal) {
        return record(EXPENSES, movement, principal, movement.amountMinor());
    }

    @PostMapping(
            path = INCOMES,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Recorded> recordIncome(
            @RequestHeader(IdempotentEndpoints.HEADER) String idempotencyKey,
            @RequestBody Movement movement,
            Principal principal) {
        return record(INCOMES, movement, principal, -movement.amountMinor());
    }

    /** No request body and no response body. */
    @PostMapping(PINGS)
    public ResponseEntity<Void> ping(@RequestHeader(IdempotentEndpoints.HEADER) String idempotencyKey) {
        pings.incrementAndGet();
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Recorded> record(String path, Movement movement, Principal principal, long toCategory) {
        JournalEntry entry = ledger.post(new NewJournalEntry(
                movement.ledgerId(),
                LocalDate.of(2026, 11, 10),
                "Idempotency test",
                UUID.fromString(principal.getName()),
                List.of(
                        new Posting(movement.categoryId(), new Money(toCategory, EUR)),
                        new Posting(movement.accountId(), new Money(-toCategory, EUR)))));
        afterNextPosting.getAndSet(IdempotencyTestEndpoint::nothing).run();
        return ResponseEntity.created(URI.create(path + "/" + entry.id()))
                .body(new Recorded(entry.id(), movement.amountMinor()));
    }
}
