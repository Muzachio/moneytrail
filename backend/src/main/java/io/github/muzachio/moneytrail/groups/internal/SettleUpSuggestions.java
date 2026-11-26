package io.github.muzachio.moneytrail.groups.internal;

import io.github.muzachio.moneytrail.groups.internal.Balances.MemberBalance;
import io.github.muzachio.moneytrail.shared.Money;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * ADR-0012's settle-up suggestions: the member who owes the most pays the member who is owed the
 * most, as much as clears one of them, until every balance is zero.
 *
 * <p>Each payment brings at least one balance to zero, and the last one two, so there are at most
 * {@code n − 1} payments for {@code n} members with a balance. It is not always the fewest: finding
 * those is NP-hard, and the greedy answer is simple and never worse than {@code n − 1}. Ties go to
 * the member whose id comes first as text, so the same balances always give the same suggestions,
 * whatever order they come in.
 */
final class SettleUpSuggestions {

    /** {@code from} pays {@code to} {@code amount}, which is above zero. */
    record Suggestion(UUID from, UUID to, Money amount) {}

    /** How much someone still owes, or is still owed: above zero either way. */
    private record Party(UUID memberId, long amount) {}

    /** The largest amount first; between equal amounts, the member id that comes first as text. */
    private static final Comparator<Party> FIRST = Comparator.comparingLong(Party::amount)
            .reversed()
            .thenComparing(party -> party.memberId().toString());

    private SettleUpSuggestions() {}

    /**
     * @param balances each member's balance, every one in the same currency, adding up to zero as a
     *     group's always do
     * @throws IllegalArgumentException for balances that break those rules, or name a member twice
     */
    static List<Suggestion> suggest(Collection<MemberBalance> balances) {
        if (balances.isEmpty()) {
            return List.of();
        }
        Currency currency = balances.iterator().next().balance().currency();
        PriorityQueue<Party> debtors = new PriorityQueue<>(FIRST);
        PriorityQueue<Party> creditors = new PriorityQueue<>(FIRST);
        Set<UUID> seen = new HashSet<>();
        long sum = 0;
        for (MemberBalance member : balances) {
            Money balance = member.balance();
            if (!balance.currency().equals(currency)) {
                throw new IllegalArgumentException("The balances of a group are all in one currency");
            }
            if (!seen.add(member.userId())) {
                throw new IllegalArgumentException("Member " + member.userId() + " has two balances");
            }
            sum = Math.addExact(sum, balance.minor());
            if (balance.minor() < 0) {
                debtors.add(new Party(member.userId(), Math.negateExact(balance.minor())));
            } else if (balance.minor() > 0) {
                creditors.add(new Party(member.userId(), balance.minor()));
            }
        }
        if (sum != 0) {
            throw new IllegalArgumentException("The balances of a group add up to zero; these add up to " + sum);
        }
        // What the debtors owe is what the creditors are owed, so both run out together.
        List<Suggestion> suggestions = new ArrayList<>();
        while (!debtors.isEmpty()) {
            Party debtor = debtors.remove();
            Party creditor = creditors.remove();
            long amount = Math.min(debtor.amount(), creditor.amount());
            suggestions.add(new Suggestion(debtor.memberId(), creditor.memberId(), new Money(amount, currency)));
            if (debtor.amount() > amount) {
                debtors.add(new Party(debtor.memberId(), debtor.amount() - amount));
            }
            if (creditor.amount() > amount) {
                creditors.add(new Party(creditor.memberId(), creditor.amount() - amount));
            }
        }
        return suggestions;
    }
}
