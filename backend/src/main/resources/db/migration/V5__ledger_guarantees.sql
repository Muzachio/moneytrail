-- The database enforces the ledger's invariants too (ADR-0004), so neither a bug in LedgerService
-- nor a manual query can break them. The composite foreign key on postings (V4) already keeps each
-- posting in its account's currency.

-- Every journal entry has at least 2 postings, and they sum to zero in each currency. Checked at
-- commit: the entry and its postings are inserted one statement at a time, so the rule only holds
-- once all of them are in. SUM over bigint gives numeric, which cannot overflow.
CREATE FUNCTION check_journal_entry_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    entry uuid;
BEGIN
    -- An IF, not a CASE: PL/pgSQL resolves every field of an expression, and NEW.entry_id does not
    -- exist on journal_entries.
    IF TG_TABLE_NAME = 'journal_entries' THEN
        entry := NEW.id;
    ELSE
        entry := NEW.entry_id;
    END IF;
    IF (SELECT count(*) FROM postings WHERE entry_id = entry) < 2 THEN
        RAISE EXCEPTION 'Journal entry % has fewer than 2 postings', entry
            USING ERRCODE = 'check_violation', CONSTRAINT = 'journal_entry_has_two_postings';
    END IF;
    IF EXISTS (SELECT FROM postings WHERE entry_id = entry GROUP BY currency HAVING sum(amount_minor) <> 0) THEN
        RAISE EXCEPTION 'Journal entry % does not sum to zero in every currency', entry
            USING ERRCODE = 'check_violation', CONSTRAINT = 'journal_entry_balanced';
    END IF;
    RETURN NULL;
END
$$;

-- On journal_entries, so an entry without any posting is caught; on postings, so a posting added
-- later to an existing entry is caught. Rows are never updated or deleted, so INSERT is enough.
CREATE CONSTRAINT TRIGGER journal_entries_balanced
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_entry_balanced();

CREATE CONSTRAINT TRIGGER postings_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_entry_balanced();

-- Append-only: a mistake is corrected by a reversal plus a new entry, never by changing history.
CREATE FUNCTION reject_ledger_history_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: the ledger is append-only, post a reversal instead',
        TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END
$$;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_history_change();

CREATE TRIGGER postings_append_only
    BEFORE UPDATE OR DELETE ON postings
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_history_change();

-- TRUNCATE skips row triggers, so it gets its own.
CREATE TRIGGER journal_entries_no_truncate
    BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_history_change();

CREATE TRIGGER postings_no_truncate
    BEFORE TRUNCATE ON postings
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_history_change();
