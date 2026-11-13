-- The transactions list (#46) pages through a ledger's entries newest first, by (occurred_on, id),
-- with a keyset condition instead of an OFFSET. With this index each page is a short backward scan
-- that starts at the cursor and stops after one page, however far into the month it is. The monthly
-- sums of the accounts (#44) and of the list read the same range of days.
CREATE INDEX journal_entries_ledger_id_occurred_on_id_idx ON journal_entries (ledger_id, occurred_on, id);
