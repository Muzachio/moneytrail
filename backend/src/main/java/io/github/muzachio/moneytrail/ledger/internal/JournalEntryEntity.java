package io.github.muzachio.moneytrail.ledger.internal;

import io.github.muzachio.moneytrail.ledger.JournalEntry;
import io.github.muzachio.moneytrail.ledger.NewJournalEntry;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Append-only (ADR-0004): {@code @Immutable} makes Hibernate skip dirty checking and never write an
 * {@code UPDATE}, and {@link JournalEntries} offers no way to delete. A correction is a new entry.
 */
@Entity
@Immutable
@Table(name = "journal_entries")
class JournalEntryEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @SuppressWarnings("NullAway.Init") // Hibernate assigns it on persist.
    private UUID id;

    private UUID ledgerId;

    private LocalDate occurredOn;

    private String description;

    private UUID createdBy;

    private Instant createdAt;

    // Both links are written with the entry itself, never afterwards (V4, V9).
    private @Nullable UUID reversesEntryId;

    private @Nullable UUID replacesEntryId;

    // Persisted together with the entry; in insertion order when read back.
    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<PostingEntity> postings = new ArrayList<>();

    @SuppressWarnings("NullAway.Init") // For Hibernate, which sets the fields itself.
    protected JournalEntryEntity() {}

    JournalEntryEntity(NewJournalEntry entry) {
        this(entry, null, null);
    }

    private JournalEntryEntity(NewJournalEntry entry, @Nullable UUID reversesEntryId, @Nullable UUID replacesEntryId) {
        this.ledgerId = entry.ledgerId();
        this.occurredOn = entry.occurredOn();
        this.description = entry.description();
        this.createdBy = entry.createdBy();
        // Postgres keeps microseconds: the time returned now is the one read back later.
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.reversesEntryId = reversesEntryId;
        this.replacesEntryId = replacesEntryId;
        entry.postings().forEach(posting -> postings.add(new PostingEntity(this, posting)));
    }

    static JournalEntryEntity reversal(NewJournalEntry reversal, UUID reversedEntryId) {
        return new JournalEntryEntity(reversal, reversedEntryId, null);
    }

    static JournalEntryEntity replacement(NewJournalEntry replacement, UUID replacedEntryId) {
        return new JournalEntryEntity(replacement, null, replacedEntryId);
    }

    JournalEntry toJournalEntry() {
        return new JournalEntry(
                id,
                ledgerId,
                occurredOn,
                description,
                createdBy,
                createdAt,
                reversesEntryId,
                replacesEntryId,
                postings.stream().map(PostingEntity::toPosting).toList());
    }
}
