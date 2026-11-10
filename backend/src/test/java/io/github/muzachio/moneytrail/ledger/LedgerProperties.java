package io.github.muzachio.moneytrail.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.shared.DomainException;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.BeforeTry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestContextManager;

/**
 * The ledger's invariants under random input, against a real Postgres. jqwik generates the entries
 * as plans (an account's position and an amount), since the accounts only exist once a try has
 * opened them: every try gets a fresh ledger, so tries and shrinking never see each other's data.
 *
 * <p>No class-level annotations here: Spring would then build a second application context, and
 * with it a second Postgres container, instead of sharing the one of the other integration tests.
 */
class LedgerProperties extends PostgresIntegrationTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");

    /** The accounts of every try's ledger: positions 0 to 3 are in EUR, 4 and 5 in USD. */
    private static final List<Currency> CURRENCIES = List.of(EUR, EUR, EUR, EUR, USD, USD);

    private static final int EUR_ACCOUNTS = 4;

    /** A billion in major units: far beyond real use, and sums of a whole try still fit a long. */
    private static final long LARGEST_AMOUNT = 100_000_000_000L;

    record PostingPlan(int account, long minor) {}

    /** How an entry breaks a rule, and the code {@link LedgerService#post} must answer with. */
    enum Flaw {
        NO_POSTINGS("too_few_postings"),
        ONE_POSTING("too_few_postings"),
        ZERO_AMOUNT("zero_amount"),
        UNBALANCED("unbalanced_entry"),
        AMOUNT_TOO_LARGE("amount_too_large"),
        UNKNOWN_ACCOUNT("account_not_found"),
        ACCOUNT_OF_ANOTHER_LEDGER("account_not_in_ledger"),
        WRONG_CURRENCY("currency_mismatch");

        private final String code;

        Flaw(String code) {
            this.code = code;
        }
    }

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    private UUID userId;
    private Ledger personal;
    private List<Account> accounts;
    private Account someoneElses;

    // jqwik is a test engine of its own, so Spring's JUnit Jupiter extension does not run here. This
    // is the part of it the properties need: the shared application context, injected.
    @BeforeProperty
    void injectSpringBeans() throws Exception {
        new TestContextManager(getClass()).prepareTestInstance(this);
    }

    @BeforeTry
    void openLedgers() {
        userId = LedgerTestUsers.create(jdbc);
        personal = ledger.openLedger(LedgerOwner.user(userId));
        accounts = new ArrayList<>();
        for (int position = 0; position < CURRENCIES.size(); position++) {
            accounts.add(ledger.openAccount(new NewAccount(
                    personal.id(), AccountKind.ASSET, null, "Account " + position, CURRENCIES.get(position))));
        }
        Ledger another = ledger.openLedger(LedgerOwner.user(LedgerTestUsers.create(jdbc)));
        someoneElses = ledger.openAccount(new NewAccount(another.id(), AccountKind.ASSET, null, "Checking", EUR));
    }

    @Property(tries = 100)
    void validEntriesStayBalancedAndEveryBalanceEqualsTheSumOfItsPostings(
            @ForAll("validEntries") List<List<PostingPlan>> entries) {
        long[] expected = new long[accounts.size()];

        for (List<PostingPlan> entry : entries) {
            JournalEntry posted = ledger.post(entryOf(postings(entry)));

            assertThat(ledger.findEntry(posted.id())).contains(posted);
            entry.forEach(posting -> expected[posting.account()] += posting.minor());
        }

        assertThat(entriesInLedger()).isEqualTo(entries.size());
        assertThat(entriesBrokenInTheDatabase()).isZero();
        for (int position = 0; position < accounts.size(); position++) {
            Account account = accounts.get(position);
            assertThat(ledger.balance(account.id())).isEqualTo(new Money(expected[position], account.currency()));
            assertThat(sumOfPostings(account.id())).isEqualTo(expected[position]);
        }
    }

    @Property(tries = 100)
    void anInvalidEntryIsRejectedAndLeavesEveryBalanceUnchanged(
            @ForAll("validEntries") List<List<PostingPlan>> earlier,
            @ForAll("validEntry") List<PostingPlan> wouldBeValid,
            @ForAll Flaw flaw,
            @ForAll @IntRange(max = 5) int account,
            @ForAll @LongRange(min = 1, max = LARGEST_AMOUNT) long amount) {
        earlier.forEach(entry -> ledger.post(entryOf(postings(entry))));
        Map<UUID, Money> balancesBefore = balances();
        NewJournalEntry invalid = entryOf(flawed(postings(wouldBeValid), flaw, accounts.get(account), amount));

        assertThatThrownBy(() -> ledger.post(invalid))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo(flaw.code));

        assertThat(balances()).isEqualTo(balancesBefore);
        assertThat(entriesInLedger()).isEqualTo(earlier.size());
        assertThat(entriesBrokenInTheDatabase()).isZero();
        balancesBefore.forEach(
                (accountId, balance) -> assertThat(sumOfPostings(accountId)).isEqualTo(balance.minor()));
    }

    @Provide
    Arbitrary<List<List<PostingPlan>>> validEntries() {
        return validEntry().list().ofMinSize(1).ofMaxSize(8);
    }

    /** Balanced in EUR, in USD, or in both at once. */
    @Provide
    Arbitrary<List<PostingPlan>> validEntry() {
        Arbitrary<List<PostingPlan>> eur = balancedPostings(0, EUR_ACCOUNTS - 1);
        Arbitrary<List<PostingPlan>> usd = balancedPostings(EUR_ACCOUNTS, CURRENCIES.size() - 1);
        Arbitrary<List<PostingPlan>> both = Combinators.combine(eur, usd)
                .as((first, second) ->
                        Stream.concat(first.stream(), second.stream()).toList());
        return Arbitraries.oneOf(eur, usd, both);
    }

    // One to four free postings, then one that closes the entry with whatever is missing for zero.
    // Any of them may land on the same account, which an entry is allowed to name more than once.
    private static Arbitrary<List<PostingPlan>> balancedPostings(int firstAccount, int lastAccount) {
        Arbitrary<Integer> account = Arbitraries.integers().between(firstAccount, lastAccount);
        Arbitrary<Long> amount =
                Arbitraries.longs().between(-LARGEST_AMOUNT, LARGEST_AMOUNT).filter(minor -> minor != 0);
        Arbitrary<List<PostingPlan>> free = Combinators.combine(account, amount)
                .as(PostingPlan::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(4);
        return Combinators.combine(free, account).as((postings, closingAccount) -> {
            long missing = -postings.stream().mapToLong(PostingPlan::minor).sum();
            List<PostingPlan> balanced = new ArrayList<>(postings);
            // Already zero only with two or more postings, as none of them is zero.
            if (missing != 0) {
                balanced.add(new PostingPlan(closingAccount, missing));
            }
            return balanced;
        });
    }

    /**
     * Breaks exactly one rule of a valid entry. Whatever is added comes in pairs that cancel out,
     * unless being unbalanced is the flaw, so no other rule can be the one that refuses the entry.
     */
    private List<Posting> flawed(List<Posting> valid, Flaw flaw, Account account, long amount) {
        Currency currency = account.currency();
        Money money = new Money(amount, currency);
        return switch (flaw) {
            case NO_POSTINGS -> List.of();
            case ONE_POSTING -> valid.subList(0, 1);
            case ZERO_AMOUNT -> with(valid, List.of(new Posting(account.id(), Money.zero(currency))));
            case UNBALANCED -> with(valid, List.of(new Posting(account.id(), money)));
            // The debits pass Long.MAX_VALUE, although the entry would balance.
            case AMOUNT_TOO_LARGE ->
                with(valid, pair(account.id(), new Money(Long.MAX_VALUE, currency)), pair(account.id(), money));
            case UNKNOWN_ACCOUNT -> with(valid, pair(UUID.randomUUID(), money));
            case ACCOUNT_OF_ANOTHER_LEDGER -> with(valid, pair(someoneElses.id(), new Money(amount, EUR)));
            case WRONG_CURRENCY -> with(valid, pair(account.id(), new Money(amount, currency.equals(EUR) ? USD : EUR)));
        };
    }

    @SafeVarargs
    private static List<Posting> with(List<Posting> valid, List<Posting>... added) {
        List<Posting> all = new ArrayList<>(valid);
        Stream.of(added).forEach(all::addAll);
        return all;
    }

    private static List<Posting> pair(UUID accountId, Money amount) {
        return List.of(new Posting(accountId, amount), new Posting(accountId, amount.negate()));
    }

    private List<Posting> postings(List<PostingPlan> plans) {
        return plans.stream()
                .map(plan -> {
                    Account account = accounts.get(plan.account());
                    return new Posting(account.id(), new Money(plan.minor(), account.currency()));
                })
                .toList();
    }

    private NewJournalEntry entryOf(List<Posting> postings) {
        return new NewJournalEntry(personal.id(), LocalDate.of(2026, 11, 8), "Generated", userId, postings);
    }

    private Map<UUID, Money> balances() {
        Map<UUID, Money> balances = new LinkedHashMap<>();
        Stream.concat(accounts.stream(), Stream.of(someoneElses))
                .forEach(account -> balances.put(account.id(), ledger.balance(account.id())));
        return balances;
    }

    private long entriesInLedger() {
        return jdbc.sql("SELECT count(*) FROM journal_entries WHERE ledger_id = :ledgerId")
                .param("ledgerId", personal.id())
                .query(Long.class)
                .single();
    }

    /** Entries of this ledger with fewer than 2 postings, or not summing to zero in some currency. */
    private long entriesBrokenInTheDatabase() {
        return jdbc.sql("""
                        SELECT count(*) FROM journal_entries entry
                        WHERE entry.ledger_id = :ledgerId
                          AND ((SELECT count(*) FROM postings WHERE entry_id = entry.id) < 2
                               OR EXISTS (SELECT FROM postings WHERE entry_id = entry.id
                                          GROUP BY currency HAVING sum(amount_minor) <> 0))""").param("ledgerId", personal.id()).query(Long.class).single();
    }

    private long sumOfPostings(UUID accountId) {
        return jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM postings WHERE account_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }
}
