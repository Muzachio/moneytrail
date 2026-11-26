-- Which journal entries of a group's ledger are settlements (ADR-0012): payments from one member to
-- another. Only the groups module writes it.
--
-- A settlement posts `from +amount` and `to −amount`, exactly what an expense that one member paid
-- for one other member posts, so the postings cannot tell the two apart. An expense has its split
-- in expense_splits; a settlement has its row here. Who paid and who was paid are read from the
-- postings, like who paid an expense. Like the entries, the rows are never changed: a correction is
-- a new entry with a row of its own.
CREATE TABLE settlements (
    entry_id uuid PRIMARY KEY REFERENCES journal_entries (id)
);
