-- How each group expense is split (ADR-0012): one row for every person in the split of an expense,
-- next to the journal entry the expense was posted as. Only the groups module writes it.
--
-- The inputs are kept because they cannot be read back from the postings: 1 share of 3 and 33.33 %
-- can give the same cents. So is a share of zero, which posts nothing. Like the entries, the rows
-- are never changed: a correction is a new entry with rows of its own.
CREATE TABLE expense_splits (
    entry_id    uuid   NOT NULL REFERENCES journal_entries (id),
    user_id     uuid   NOT NULL REFERENCES users (id),
    method      text   NOT NULL CHECK (method IN ('EQUALLY', 'SHARES', 'PERCENTAGES', 'EXACT_AMOUNTS')),
    -- 1 when splitting equally, the shares, the basis points, or the exact amount in minor units.
    input_value bigint NOT NULL CHECK (input_value > 0),
    share_minor bigint NOT NULL CHECK (share_minor >= 0),
    PRIMARY KEY (entry_id, user_id)
);
