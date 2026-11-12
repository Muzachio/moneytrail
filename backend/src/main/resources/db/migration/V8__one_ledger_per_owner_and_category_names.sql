-- Every user gets one personal ledger, opened on sign-in (#42), and a group will get one too (M2).
-- Unique, so two sign-ins at the same time cannot open two: the second INSERT ... ON CONFLICT waits
-- for the first transaction and then does nothing. No code path on main opened a ledger outside the
-- tests before this migration, so no existing row can break it.
ALTER TABLE ledgers ADD CONSTRAINT ledgers_owner_key UNIQUE (owner_type, owner_id);

-- Category names (EXPENSE and INCOME accounts) are unique per kind among a ledger's active ones,
-- ignoring case, so no one sees two "Food" when picking a category. Archived categories may share a
-- name, and so may other kinds: two members of a group can both be called Ana (M2). pg_c_utf8 makes
-- lower() fold every Unicode letter whatever the database's locale, so "SAÚDE" matches "saúde".
CREATE UNIQUE INDEX accounts_category_name_key
    ON accounts (ledger_id, kind, lower(name COLLATE pg_c_utf8))
    WHERE archived_at IS NULL AND kind IN ('EXPENSE', 'INCOME');
