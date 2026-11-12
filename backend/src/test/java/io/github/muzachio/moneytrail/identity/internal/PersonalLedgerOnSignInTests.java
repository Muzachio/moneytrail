package io.github.muzachio.moneytrail.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.Ledger;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a sign-in sets up for the personal module (#42): identity publishes UserSignedIn in the
 * sign-in transaction, and personal opens the ledger with the default categories in it. Here,
 * because only this package can sign someone in.
 */
class PersonalLedgerOnSignInTests extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final int SIGN_INS_AT_ONCE = 4;

    @Autowired
    private Users users;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void theFirstSignInOpensAPersonalLedgerWithTheDefaultCategoriesAndOpeningBalances() {
        UUID userId = users.signIn(newProfile());

        Ledger personal = ledger.findLedger(LedgerOwner.user(userId)).orElseThrow();
        List<Account> accounts = ledger.accounts(personal.id());
        assertThat(accounts)
                .extracting(Account::kind, Account::subtype, Account::name)
                .containsExactlyInAnyOrder(
                        tuple(AccountKind.EXPENSE, "housing", "Housing"),
                        tuple(AccountKind.EXPENSE, "food", "Food"),
                        tuple(AccountKind.EXPENSE, "transport", "Transport"),
                        tuple(AccountKind.EXPENSE, "bills", "Bills"),
                        tuple(AccountKind.EXPENSE, "shopping", "Shopping"),
                        tuple(AccountKind.EXPENSE, "leisure", "Leisure"),
                        tuple(AccountKind.EXPENSE, "health", "Health"),
                        tuple(AccountKind.EXPENSE, "other", "Other"),
                        tuple(AccountKind.INCOME, "salary", "Salary"),
                        tuple(AccountKind.INCOME, "refunds", "Refunds"),
                        tuple(AccountKind.INCOME, "gifts", "Gifts"),
                        tuple(AccountKind.INCOME, "other-income", "Other income"),
                        tuple(AccountKind.EQUITY, "opening-balances", "Opening balances"));
        assertThat(accounts).allSatisfy(account -> {
            assertThat(account.currency()).isEqualTo(EUR);
            assertThat(account.archived()).isFalse();
            assertThat(ledger.balance(account.id())).isEqualTo(new Money(0, EUR));
        });
    }

    @Test
    void signingInAgainOpensNoSecondLedger() {
        SignInProfile profile = newProfile();
        UUID userId = users.signIn(profile);

        users.signIn(profile);
        users.signIn(profile);

        assertThat(ledgersOf(userId)).isOne();
        assertThat(accountsOf(userId)).isEqualTo(13);
    }

    @Test
    void aUserFromBeforeLedgersGetsOneOnTheirNextSignIn() {
        SignInProfile profile = newProfile();
        UUID userId = createUserTheWayM0Did(profile);
        assertThat(ledgersOf(userId)).isZero();

        assertThat(users.signIn(profile)).isEqualTo(userId);

        assertThat(ledgersOf(userId)).isOne();
        assertThat(accountsOf(userId)).isEqualTo(13);
    }

    /** Several tabs finishing the first sign-in of one account at the same moment. */
    @Test
    void firstSignInsAtTheSameTimeMakeOneUserWithOneLedger() throws Exception {
        SignInProfile profile = newProfile();

        List<UUID> userIds = signInAtOnce(profile);

        UUID userId = userIds.getFirst();
        assertThat(userIds).containsOnly(userId);
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email = :email")
                        .param("email", profile.email())
                        .query(Long.class)
                        .single())
                .isOne();
        assertThat(ledgersOf(userId)).isOne();
        assertThat(accountsOf(userId)).isEqualTo(13);
    }

    @Test
    void signInsAtTheSameTimeOfAUserFromBeforeLedgersOpenOneLedger() throws Exception {
        SignInProfile profile = newProfile();
        UUID userId = createUserTheWayM0Did(profile);

        assertThat(signInAtOnce(profile)).containsOnly(userId);

        assertThat(ledgersOf(userId)).isOne();
        assertThat(accountsOf(userId)).isEqualTo(13);
    }

    @Test
    void theLedgerCommitsWithTheSignInOrNotAtAll() {
        SignInProfile profile = newProfile();

        UUID userId = Objects.requireNonNull(transactions.execute(status -> {
            UUID id = users.signIn(profile);
            assertThat(ledgersOf(id)).isOne();
            status.setRollbackOnly();
            return id;
        }));

        assertThat(users.find(userId)).isEmpty();
        assertThat(ledgersOf(userId)).isZero();
    }

    private List<UUID> signInAtOnce(SignInProfile profile) throws Exception {
        CyclicBarrier allReady = new CyclicBarrier(SIGN_INS_AT_ONCE);
        ExecutorService threads = Executors.newFixedThreadPool(SIGN_INS_AT_ONCE);
        try {
            List<Future<UUID>> signIns = new ArrayList<>();
            for (int i = 0; i < SIGN_INS_AT_ONCE; i++) {
                signIns.add(threads.submit(() -> {
                    allReady.await(30, TimeUnit.SECONDS);
                    return users.signIn(profile);
                }));
            }
            List<UUID> userIds = new ArrayList<>();
            // get() rethrows what a sign-in threw, such as a duplicate key.
            for (Future<UUID> signIn : signIns) {
                userIds.add(signIn.get(1, TimeUnit.MINUTES));
            }
            return userIds;
        } finally {
            threads.shutdownNow();
        }
    }

    private static SignInProfile newProfile() {
        return SignInProfile.of("google", TestUsers.googleAccount("Ana Teste", true));
    }

    /** A user and identity as M0's sign-in left them: no ledger. */
    private UUID createUserTheWayM0Did(SignInProfile profile) {
        UUID userId = jdbc.sql("INSERT INTO users (email, name) VALUES (:email, :name) RETURNING id")
                .param("email", profile.email())
                .param("name", profile.name())
                .query(UUID.class)
                .single();
        jdbc.sql("INSERT INTO identities (provider, subject, user_id) VALUES (:provider, :subject, :userId)")
                .param("provider", profile.provider())
                .param("subject", profile.subject())
                .param("userId", userId)
                .update();
        return userId;
    }

    private long ledgersOf(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM ledgers WHERE owner_type = 'USER' AND owner_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private long accountsOf(UUID userId) {
        return jdbc.sql("""
                        SELECT count(*) FROM accounts JOIN ledgers ON ledgers.id = accounts.ledger_id
                        WHERE ledgers.owner_type = 'USER' AND ledgers.owner_id = :userId""").param("userId", userId).query(Long.class).single();
    }
}
