package io.github.muzachio.moneytrail.ledger.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * A plain {@link Repository} instead of a {@code CrudRepository}, so that only the methods below
 * exist: entries are added and read, never deleted. Postings have no repository of their own.
 */
interface JournalEntries extends Repository<JournalEntryEntity, UUID> {

    // Flushed at once, so that an entry that reverses or replaces one already reversed or replaced
    // fails here, where DefaultLedgerService can tell the caller why.
    JournalEntryEntity saveAndFlush(JournalEntryEntity entry);

    Optional<JournalEntryEntity> findById(UUID id);

    Optional<JournalEntryEntity> findByReversesEntryId(UUID reversedEntryId);

    Optional<JournalEntryEntity> findByReplacesEntryId(UUID replacedEntryId);
}
