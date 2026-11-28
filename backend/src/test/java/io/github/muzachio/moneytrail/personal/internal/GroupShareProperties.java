package io.github.muzachio.moneytrail.personal.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.personal.internal.GroupMirrorStore.EntryType;
import io.github.muzachio.moneytrail.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/**
 * ADR-0015's posting rules: whatever a member paid and whatever their share, the personal entry
 * balances, holds no posting of zero, and moves the group's account by exactly what their account in
 * the group moved.
 */
class GroupShareProperties {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Money ZERO = Money.zero(EUR);
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID PAYMENT = UUID.randomUUID();
    private static final UUID GROUP = UUID.randomUUID();

    @Property
    void anExpenseBalancesAndMovesTheGroupAccountByTheMembersChange(
            @ForAll @LongRange(min = 1, max = 1_000_000) long total,
            @ForAll @LongRange(min = 0, max = 1_000_000) long shareSeed,
            @ForAll boolean paid,
            @ForAll boolean inTheSplit) {
        long share = inTheSplit ? shareSeed % (total + 1) : 0;
        GroupShare read = GroupShare.of(expense(total, share, paid), MEMBER, ZERO);

        assertThat(read.paid().minor()).isEqualTo(paid ? total : 0);
        assertThat(read.share().minor()).isEqualTo(share);
        if (!read.involved()) {
            return;
        }
        List<Posting> postings = read.postings(EntryType.EXPENSE, CATEGORY, PAYMENT, GROUP);

        assertBalancedWithoutZeros(postings);
        assertThat(sumTo(postings, GROUP)).isEqualTo(read.change().minor());
        assertThat(sumTo(postings, CATEGORY)).as("the share is spending").isEqualTo(share);
        assertThat(sumTo(postings, PAYMENT)).as("what left the account").isEqualTo(paid ? -total : 0);
    }

    @Property
    void aSettlementIsATransferOfWhatWasPaidOrReceived(
            @ForAll @LongRange(min = 1, max = 1_000_000) long amount, @ForAll boolean memberPays) {
        JournalEntry settlement = entry(List.of(
                new Posting(memberPays ? MEMBER : OTHER, money(amount)),
                new Posting(memberPays ? OTHER : MEMBER, money(-amount))));
        GroupShare read = GroupShare.of(settlement, MEMBER, ZERO);
        List<Posting> postings = read.postings(EntryType.SETTLEMENT, CATEGORY, PAYMENT, GROUP);

        assertBalancedWithoutZeros(postings);
        assertThat(postings).hasSize(2).noneMatch(posting -> posting.accountId().equals(CATEGORY));
        assertThat(sumTo(postings, GROUP)).isEqualTo(memberPays ? amount : -amount);
        assertThat(sumTo(postings, PAYMENT)).isEqualTo(memberPays ? -amount : amount);
    }

    /** The ADR's example: €30 paid, €10 share: Food +10, checking −30, group +20. */
    @Example
    void payingThirtyWithAShareOfTenOwesTwentyToTheMember() {
        List<Posting> postings = GroupShare.of(expense(3000, 1000, true), MEMBER, ZERO)
                .postings(EntryType.EXPENSE, CATEGORY, PAYMENT, GROUP);

        assertThat(postings)
                .containsExactly(
                        new Posting(GROUP, money(2000)),
                        new Posting(CATEGORY, money(1000)),
                        new Posting(PAYMENT, money(-3000)));
    }

    @Example
    void aMemberWithNoPostingIsNotInvolved() {
        assertThat(GroupShare.of(expense(3000, 0, false), MEMBER, ZERO).involved())
                .isFalse();
    }

    /**
     * An expense of {@code total}: paid by the member or by someone else, the member's share, and the
     * rest of the split on the other member, as the groups module posts it (ADR-0012).
     */
    private static JournalEntry expense(long total, long share, boolean memberPaid) {
        List<Posting> postings = new ArrayList<>();
        postings.add(new Posting(memberPaid ? MEMBER : OTHER, money(total)));
        if (share > 0) {
            postings.add(new Posting(MEMBER, money(-share)));
        }
        if (total - share > 0) {
            postings.add(new Posting(OTHER, money(-(total - share))));
        }
        return entry(postings);
    }

    private static JournalEntry entry(List<Posting> postings) {
        return new JournalEntry(
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.of(2026, 11, 28),
                "Dinner",
                UUID.randomUUID(),
                Instant.now(),
                null,
                null,
                postings);
    }

    private static void assertBalancedWithoutZeros(List<Posting> postings) {
        assertThat(postings)
                .hasSizeGreaterThanOrEqualTo(2)
                .noneMatch(posting -> posting.amount().isZero());
        assertThat(postings.stream()
                        .mapToLong(posting -> posting.amount().minor())
                        .sum())
                .isZero();
    }

    private static long sumTo(List<Posting> postings, UUID accountId) {
        return postings.stream()
                .filter(posting -> posting.accountId().equals(accountId))
                .mapToLong(posting -> posting.amount().minor())
                .sum();
    }

    private static Money money(long minor) {
        return new Money(minor, EUR);
    }
}
