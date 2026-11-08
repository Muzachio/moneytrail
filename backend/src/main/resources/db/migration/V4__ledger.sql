-- The double-entry ledger (ADR-0004, docs/architecture.md "The ledger"). Amounts are bigint minor
-- units, never floating point. Only the ledger module writes these tables.

-- One per person now; groups get theirs in M2.
CREATE TABLE ledgers (
    id         uuid PRIMARY KEY DEFAULT uuidv7(),
    owner_type text NOT NULL CHECK (owner_type IN ('USER', 'GROUP')),
    owner_id   uuid NOT NULL
);

-- Categories are accounts too (EXPENSE and INCOME). GROUP_MEMBER joins the kinds with groups (M2).
CREATE TABLE accounts (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    ledger_id   uuid        NOT NULL REFERENCES ledgers (id),
    kind        text        NOT NULL CHECK (kind IN ('ASSET', 'LIABILITY', 'INCOME', 'EXPENSE', 'EQUITY')),
    subtype     text,
    name        text        NOT NULL,
    currency    char(3)     NOT NULL,
    archived_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- Redundant next to the primary key, but a foreign key needs a unique target: postings use it
    -- to stay in their account's currency.
    UNIQUE (id, currency)
);

CREATE INDEX accounts_ledger_id_idx ON accounts (ledger_id);

-- Append-only: a mistake is corrected by a reversal plus a new entry, never by an UPDATE.
CREATE TABLE journal_entries (
    id                uuid        PRIMARY KEY DEFAULT uuidv7(),
    ledger_id         uuid        NOT NULL REFERENCES ledgers (id),
    occurred_on       date        NOT NULL,
    description       text        NOT NULL,
    -- UNIQUE: an entry is reversed at most once.
    reverses_entry_id uuid        UNIQUE REFERENCES journal_entries (id),
    created_by        uuid        NOT NULL REFERENCES users (id),
    created_at        timestamptz NOT NULL DEFAULT now()
);

-- Debit positive, credit negative. The postings of an entry sum to zero per currency.
CREATE TABLE postings (
    id           bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id     uuid    NOT NULL REFERENCES journal_entries (id),
    account_id   uuid    NOT NULL,
    currency     char(3) NOT NULL,
    amount_minor bigint  NOT NULL CHECK (amount_minor <> 0),
    -- Composite: a posting can only be in its account's currency.
    FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
);

CREATE INDEX postings_entry_id_idx ON postings (entry_id);
CREATE INDEX postings_account_id_idx ON postings (account_id);
