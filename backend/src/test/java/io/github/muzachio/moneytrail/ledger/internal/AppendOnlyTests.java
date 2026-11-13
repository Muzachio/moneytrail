package io.github.muzachio.moneytrail.ledger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

/** Fails the build if a change opens a way to update or delete journal entries or postings. */
class AppendOnlyTests {

    @Test
    void journalEntriesAndPostingsAreImmutableEntities() {
        assertThat(JournalEntryEntity.class).hasAnnotation(Immutable.class);
        assertThat(PostingEntity.class).hasAnnotation(Immutable.class);
    }

    @Test
    void journalEntriesCanOnlyBeAddedAndRead() {
        assertThat(JournalEntries.class.getInterfaces()).containsExactly(Repository.class);
        assertThat(JournalEntries.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "saveAndFlush", "findById", "findByReversesEntryId", "findByReplacesEntryId");
    }
}
