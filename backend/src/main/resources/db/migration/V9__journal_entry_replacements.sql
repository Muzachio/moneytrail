-- Editing a transaction (#46) reverses its entry and posts the corrected one in the same database
-- transaction. The correction points to the entry it replaces, so a transaction's history can be
-- followed from its first version to its current one without changing any row: the link is written
-- with the new entry, which keeps the table append-only. UNIQUE: an entry is replaced at most once,
-- just as it is reversed at most once (reverses_entry_id, V4).
ALTER TABLE journal_entries
    ADD COLUMN replaces_entry_id uuid UNIQUE REFERENCES journal_entries (id),
    -- A reversal only cancels an entry; the correction is an entry of its own.
    ADD CONSTRAINT journal_entries_reversal_is_no_replacement
        CHECK (reverses_entry_id IS NULL OR replaces_entry_id IS NULL);
