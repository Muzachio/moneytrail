package io.github.muzachio.moneytrail.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.Ledger;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.ledger.NewAccount;
import io.github.muzachio.moneytrail.shared.Money;
import io.github.muzachio.moneytrail.shared.idempotency.IdempotencyTestEndpoint.SimulatedFailure;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every case of the Idempotency-Key contract (api/openapi.yaml), through the test-only endpoints of
 * {@link IdempotencyTestEndpoint}, which record real journal entries.
 */
@AutoConfigureMockMvc
@Import(IdempotencyTestEndpoint.class)
class IdempotentRequestsTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private IdempotencyTestEndpoint endpoint;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApplicationContext context;

    private UserWithLedger ana;

    @BeforeEach
    void createUser() {
        ana = createUserWithLedger();
    }

    @Test
    void aRetryGetsTheStoredResponseAndRecordsNothingAgain() {
        String key = newKey();

        MvcTestResult first = post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(1250));
        MvcTestResult retry = post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(1250));

        assertThat(first)
                .hasStatus(HttpStatus.CREATED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson()
                .extractingPath("$.amountMinor")
                .isEqualTo(1250);
        assertThat(retry).hasStatus(HttpStatus.CREATED).hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(retry.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());
        assertThat(retry.getResponse().getHeader(HttpHeaders.LOCATION))
                .startsWith(IdempotencyTestEndpoint.EXPENSES + "/")
                .isEqualTo(first.getResponse().getHeader(HttpHeaders.LOCATION));
        assertThat(ledger.balance(ana.food())).isEqualTo(new Money(1250, EUR));
        assertThat(entriesOf(ana)).isOne();
        assertThat(storedStatus(ana, key)).isEqualTo(201);
    }

    @Test
    void withoutTheHeaderTheRequestIsRefused() {
        MvcTestResult result = post(IdempotencyTestEndpoint.EXPENSES, ana, null, ana.expense(1250));

        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("bad_request");
        assertThat(entriesOf(ana)).isZero();
    }

    @Test
    void anEmptyOrTooLongKeyIsRefused() {
        for (String key : List.of("", " ", "k".repeat(256))) {
            assertThat(post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(1250)))
                    .as("key of %d characters", key.length())
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("bad_request");
        }
        assertThat(entriesOf(ana)).isZero();
        assertThat(post(IdempotencyTestEndpoint.EXPENSES, ana, "k".repeat(255), ana.expense(1250)))
                .hasStatus(HttpStatus.CREATED);
    }

    @Test
    void theSameKeyWithAnotherBodyIsRefused() {
        String key = newKey();
        assertThat(post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(1250)))
                .hasStatus(HttpStatus.CREATED);

        MvcTestResult other = post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(990));

        assertThat(other)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("idempotency_key_reused");
        assertThat(ledger.balance(ana.food())).isEqualTo(new Money(1250, EUR));
        assertThat(entriesOf(ana)).isOne();
    }

    @Test
    void theSameKeyAndBodyOnAnotherPathIsRefused() {
        String key = newKey();
        assertThat(post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(1250)))
                .hasStatus(HttpStatus.CREATED);

        MvcTestResult other = post(IdempotencyTestEndpoint.INCOMES, ana, key, ana.expense(1250));

        assertThat(other)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("idempotency_key_reused");
        assertThat(entriesOf(ana)).isOne();
    }

    @Test
    void aRetryWhileTheFirstRequestIsStillRunningIsRefused() throws Exception {
        String key = newKey();
        CountDownLatch posted = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        endpoint.afterNextPosting(() -> {
            posted.countDown();
            await(finish);
        });
        ExecutorService firstRequest = Executors.newSingleThreadExecutor();
        try {
            Future<MvcTestResult> first =
                    firstRequest.submit(() -> post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(1250)));
            await(posted);

            MvcTestResult retry = post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(1250));

            assertThat(retry)
                    .hasStatus(HttpStatus.CONFLICT)
                    .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("idempotency_key_in_use");
            // Nothing is stored before the first request commits.
            assertThat(storedStatus(ana, key)).isNull();

            finish.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).hasStatus(HttpStatus.CREATED);
        } finally {
            finish.countDown();
            firstRequest.shutdownNow();
        }
        assertThat(post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(1250)))
                .hasStatus(HttpStatus.CREATED);
        assertThat(ledger.balance(ana.food())).isEqualTo(new Money(1250, EUR));
        assertThat(entriesOf(ana)).isOne();
    }

    @Test
    void aRequestThatFailsLeavesNothingBehindAndCanBeRetried() {
        String key = newKey();
        endpoint.afterNextPosting(() -> {
            throw new SimulatedFailure();
        });

        assertThat(post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(1250)))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(entriesOf(ana)).isZero();
        assertThat(storedStatus(ana, key)).isNull();

        assertThat(post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(1250)))
                .hasStatus(HttpStatus.CREATED);
        assertThat(ledger.balance(ana.food())).isEqualTo(new Money(1250, EUR));
    }

    /**
     * The endpoint returned and its response was stored, but the commit fails, as a deferred
     * constraint would make it: the client gets no success, and the entry and the stored response
     * are both gone, because they were in the same transaction.
     */
    @Test
    void aRequestWhoseCommitFailsLeavesNothingBehindAndCanBeRetried() {
        String key = newKey();
        endpoint.afterNextPosting(
                () -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        throw new SimulatedCommitFailure();
                    }
                }));

        MvcTestResult failed = post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(1250));

        // No response was written; a real server answers 500.
        assertThat(failed).hasFailed().failure().hasCauseInstanceOf(SimulatedCommitFailure.class);
        assertThat(entriesOf(ana)).isZero();
        assertThat(storedStatus(ana, key)).isNull();

        assertThat(post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(1250)))
                .hasStatus(HttpStatus.CREATED);
        assertThat(ledger.balance(ana.food())).isEqualTo(new Money(1250, EUR));
    }

    @Test
    void keysBelongToTheirUser() {
        UserWithLedger rui = createUserWithLedger();
        String key = newKey();

        MvcTestResult anas = post(IdempotencyTestEndpoint.EXPENSES, ana, key, ana.expense(1250));
        MvcTestResult ruis = post(IdempotencyTestEndpoint.EXPENSES, rui, key, rui.expense(1250));

        assertThat(anas).hasStatus(HttpStatus.CREATED);
        assertThat(ruis).hasStatus(HttpStatus.CREATED);
        assertThat(ruis.getResponse().getHeader(HttpHeaders.LOCATION))
                .isNotEqualTo(anas.getResponse().getHeader(HttpHeaders.LOCATION));
        assertThat(ledger.balance(ana.food())).isEqualTo(new Money(1250, EUR));
        assertThat(ledger.balance(rui.food())).isEqualTo(new Money(1250, EUR));
        assertThat(storedStatus(ana, key)).isEqualTo(201);
        assertThat(storedStatus(rui, key)).isEqualTo(201);
    }

    @Test
    void aRequestWithoutBodyAndItsEmptyResponseAreHandledToo() {
        String key = newKey();
        int pingsBefore = endpoint.pings();

        MvcTestResult first = post(IdempotencyTestEndpoint.PINGS, ana, key, null);
        MvcTestResult retry = post(IdempotencyTestEndpoint.PINGS, ana, key, null);

        assertThat(first).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(retry).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(retry.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(endpoint.pings()).isEqualTo(pingsBefore + 1);
    }

    /** Guards the real endpoints too: whatever declares the header must be wrapped. */
    @Test
    void everyEndpointThatTakesTheHeaderIsWrapped() {
        List<HandlerMethod> idempotent = context.getBeansOfType(RequestMappingHandlerMapping.class).values().stream()
                .flatMap(mapping -> mapping.getHandlerMethods().values().stream())
                .filter(handler -> IdempotentEndpoints.takesTheHeader(handler.getMethod()))
                .toList();

        assertThat(idempotent).hasSizeGreaterThanOrEqualTo(3);
        assertThat(idempotent).allSatisfy(handler -> {
            Object bean = handler.createWithResolvedBean().getBean();
            assertThat(bean).isInstanceOf(Advised.class);
            assertThat(Arrays.stream(((Advised) bean).getAdvisors()))
                    .anyMatch(advisor -> advisor instanceof PointcutAdvisor pointcutAdvisor
                            && pointcutAdvisor.getPointcut() instanceof IdempotentEndpoints);
        });
    }

    @Test
    void theCleanupIsNotPartOfTheApiProcess() {
        assertThat(context.getBeanNamesForType(ExpiredIdempotencyKeys.class)).isEmpty();
    }

    private MvcTestResult post(String path, UserWithLedger user, @Nullable String key, @Nullable String body) {
        var request = mvc.post().uri(path).with(user(user.id().toString())).with(csrf());
        if (key != null) {
            request.header(IdempotentEndpoints.HEADER, key);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private long entriesOf(UserWithLedger user) {
        return jdbc.sql("SELECT count(*) FROM journal_entries WHERE ledger_id = :ledgerId")
                .param("ledgerId", user.ledgerId())
                .query(Long.class)
                .single();
    }

    private @Nullable Integer storedStatus(UserWithLedger user, String key) {
        return jdbc.sql("SELECT response_status FROM idempotency_keys WHERE user_id = :userId AND key = :key")
                .param("userId", user.id())
                .param("key", key)
                .query(Integer.class)
                .optional()
                .orElse(null);
    }

    private UserWithLedger createUserWithLedger() {
        UUID id = jdbc.sql("INSERT INTO users (email, name) VALUES (:email, 'Idempotency Test') RETURNING id")
                .param("email", "idempotency-" + UUID.randomUUID() + "@example.com")
                .query(UUID.class)
                .single();
        Ledger personal = ledger.openLedger(LedgerOwner.user(id));
        Account checking = ledger.openAccount(new NewAccount(personal.id(), AccountKind.ASSET, null, "Checking", EUR));
        Account food = ledger.openAccount(new NewAccount(personal.id(), AccountKind.EXPENSE, null, "Food", EUR));
        return new UserWithLedger(id, personal.id(), checking.id(), food.id());
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).as("waited 30 s").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private record UserWithLedger(UUID id, UUID ledgerId, UUID checking, UUID food) {

        String expense(long amountMinor) {
            return """
                    {"ledgerId":"%s","accountId":"%s","categoryId":"%s","amountMinor":%d}""".formatted(ledgerId, checking, food, amountMinor);
        }
    }

    private static final class SimulatedCommitFailure extends RuntimeException {}
}
