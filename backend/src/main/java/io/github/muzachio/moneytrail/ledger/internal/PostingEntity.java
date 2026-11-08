package io.github.muzachio.moneytrail.ledger.internal;

import io.github.muzachio.moneytrail.ledger.Posting;
import io.github.muzachio.moneytrail.shared.Money;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only, like its {@link JournalEntryEntity}, and only ever written through it. */
@Entity
@Immutable
@Table(name = "postings")
class PostingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SuppressWarnings("NullAway.Init") // The database assigns it on insert.
    private Long id;

    // The owning side, so entry_id is part of the INSERT: an UPDATE afterwards would break
    // immutability. Only Hibernate reads it.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id")
    @SuppressWarnings("UnusedVariable")
    private JournalEntryEntity entry;

    private UUID accountId;

    // Must match the account's currency: the schema has a composite foreign key on both columns.
    @JdbcTypeCode(SqlTypes.CHAR)
    private Currency currency;

    private long amountMinor;

    @SuppressWarnings("NullAway.Init") // For Hibernate, which sets the fields itself.
    protected PostingEntity() {}

    PostingEntity(JournalEntryEntity entry, Posting posting) {
        this.entry = entry;
        this.accountId = posting.accountId();
        this.currency = posting.amount().currency();
        this.amountMinor = posting.amount().minor();
    }

    Posting toPosting() {
        return new Posting(accountId, new Money(amountMinor, currency));
    }
}
