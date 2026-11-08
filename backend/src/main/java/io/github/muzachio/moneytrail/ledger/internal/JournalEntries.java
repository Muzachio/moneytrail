package io.github.muzachio.moneytrail.ledger.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * A plain {@link Repository} instead of a {@code CrudRepository}, so that only the methods below
 * exist: entries are added and read, never deleted. Postings have no repository of their own.
 */
interface JournalEntries extends Repository<JournalEntryEntity, UUID> {

    JournalEntryEntity save(JournalEntryEntity entry);

    Optional<JournalEntryEntity> findById(UUID id);
}
