-- What each group expense was for (#117): one of the default expense categories' keys, so the app
-- shows it with the same name and icon as a person's own category. Only the groups module writes it.
--
-- A label next to the journal entry, like the split (V16), and never a ledger account: a group's
-- ledger has only its members' accounts, which is why their balances always add up to zero
-- (ADR-0012). Like the entries, a row is never changed: a correction is a new entry with a category
-- of its own, so the trail can show each version's.
CREATE TABLE expense_categories (
    entry_id uuid PRIMARY KEY REFERENCES journal_entries (id),
    category text NOT NULL
        CHECK (category IN ('housing', 'food', 'transport', 'bills', 'shopping', 'leisure', 'health', 'other'))
);

-- Every version of every expense recorded before categories existed: an expense is an entry with a
-- split, and a reversal or a settlement has none.
INSERT INTO expense_categories (entry_id, category)
SELECT DISTINCT entry_id, 'other' FROM expense_splits;
