-- Each account's balance, kept up to date in the same transaction as its postings, so reading it is
-- one row instead of a SUM over all of them (docs/architecture.md "Guarantees"). The currency is
-- the account's. LedgerService locks these rows with SELECT ... FOR UPDATE in ascending account_id
-- order before changing them, so two entries touching the same accounts cannot deadlock.
CREATE TABLE account_balances (
    account_id    uuid        PRIMARY KEY REFERENCES accounts (id),
    balance_minor bigint      NOT NULL DEFAULT 0,
    -- How many entries have changed the balance; useful when checking it against the postings.
    version       bigint      NOT NULL DEFAULT 0,
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- Accounts opened before this migration start from their postings. SUM over bigint is numeric, so
-- the cast fails loudly if a total ever left the bigint range.
INSERT INTO account_balances (account_id, balance_minor, version)
SELECT accounts.id, coalesce(sum(postings.amount_minor), 0)::bigint, count(DISTINCT postings.entry_id)
FROM accounts
LEFT JOIN postings ON postings.account_id = accounts.id
GROUP BY accounts.id;
