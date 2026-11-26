package io.github.muzachio.moneytrail.groups.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.groups.internal.Balances.MemberBalance;
import io.github.muzachio.moneytrail.groups.internal.SettleUpSuggestions.Suggestion;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * ADR-0012's promises for the settle-up suggestions, over any balances a group can have: up to ten
 * members with any ids, adding up to zero. Half the tries use small amounts, so that many members
 * owe or are owed the same and the ties are tried too.
 */
class SettleUpSuggestionsProperties {

    private static final Currency EUR = Currency.getInstance("EUR");

    @Property
    void makingTheSuggestedPaymentsBringsEveryBalanceToZero(@ForAll("balances") List<MemberBalance> balances) {
        Map<UUID, Long> left = new HashMap<>();
        balances.forEach(member -> left.put(member.userId(), member.balance().minor()));

        for (Suggestion suggestion : SettleUpSuggestions.suggest(balances)) {
            // Paying moves debt as a settlement does: the payer owes less, the receiver is owed less.
            left.merge(suggestion.from(), suggestion.amount().minor(), Math::addExact);
            left.merge(suggestion.to(), -suggestion.amount().minor(), Math::addExact);
        }

        assertThat(left.values()).containsOnly(0L);
    }

    @Property
    void thereAreAtMostOneFewerPaymentsThanMembersWithABalance(@ForAll("balances") List<MemberBalance> balances) {
        long withABalance =
                balances.stream().filter(member -> !member.balance().isZero()).count();

        assertThat(SettleUpSuggestions.suggest(balances)).hasSizeLessThanOrEqualTo((int) Math.max(0, withABalance - 1));
    }

    @Property
    void onlyDebtorsPayAndOnlyCreditorsReceiveNeverMoreThanTheirBalance(
            @ForAll("balances") List<MemberBalance> balances) {
        Map<UUID, Long> balanceOf = new HashMap<>();
        balances.forEach(
                member -> balanceOf.put(member.userId(), member.balance().minor()));
        Map<UUID, Long> paid = new HashMap<>();
        Map<UUID, Long> received = new HashMap<>();

        for (Suggestion suggestion : SettleUpSuggestions.suggest(balances)) {
            assertThat(suggestion.amount().minor()).isPositive();
            assertThat(suggestion.amount().currency()).isEqualTo(EUR);
            assertThat(balanceOf.get(suggestion.from())).isNegative();
            assertThat(balanceOf.get(suggestion.to())).isPositive();
            paid.merge(suggestion.from(), suggestion.amount().minor(), Math::addExact);
            received.merge(suggestion.to(), suggestion.amount().minor(), Math::addExact);
        }

        paid.forEach((member, amount) -> assertThat(amount).isLessThanOrEqualTo(-balanceOf.getOrDefault(member, 0L)));
        received.forEach(
                (member, amount) -> assertThat(amount).isLessThanOrEqualTo(balanceOf.getOrDefault(member, 0L)));
    }

    @Property
    void theSameBalancesInAnyOrderGiveTheSameSuggestions(
            @ForAll("balances") List<MemberBalance> balances, @ForAll long seed) {
        List<MemberBalance> shuffled = new ArrayList<>(balances);
        Collections.shuffle(shuffled, new Random(seed));

        assertThat(SettleUpSuggestions.suggest(shuffled)).isEqualTo(SettleUpSuggestions.suggest(balances));
    }

    /** Up to ten members; the last one's balance makes them add up to zero, as a group's always do. */
    @Provide
    Arbitrary<List<MemberBalance>> balances() {
        Arbitrary<Long> amounts = Arbitraries.oneOf(
                Arbitraries.longs().between(-5, 5), Arbitraries.longs().between(-1_000_000_000L, 1_000_000_000L));
        Arbitrary<UUID> ids =
                Combinators.combine(Arbitraries.longs(), Arbitraries.longs()).as(UUID::new);
        return Combinators.combine(
                        amounts.list().ofMinSize(1).ofMaxSize(9), ids.set().ofSize(10))
                .as((first, memberIds) -> {
                    List<UUID> members = new ArrayList<>(memberIds);
                    List<MemberBalance> balances = new ArrayList<>();
                    long sum = 0;
                    for (int i = 0; i < first.size(); i++) {
                        balances.add(new MemberBalance(members.get(i), new Money(first.get(i), EUR)));
                        sum += first.get(i);
                    }
                    balances.add(new MemberBalance(members.get(first.size()), new Money(-sum, EUR)));
                    return balances;
                });
    }
}
