-- A member's share of a group recorded in their personal ledger (#94, ADR-0015). Both tables belong
-- to `personal`, like category_icons: the ledger and `groups` know nothing about mirrors.

-- One row per person and group once they turned the mirror on. The row stays when they turn it off,
-- so turning it on again brings back the same account in their ledger.
CREATE TABLE group_mirrors (
    user_id            uuid    NOT NULL REFERENCES users (id),
    group_id           uuid    NOT NULL REFERENCES groups (id),
    -- Their GROUP_MEMBER account in the group's ledger: its postings in an entry say what the entry
    -- was for them (ADR-0012 keeps what a payer paid and their share apart).
    member_account_id  uuid    NOT NULL REFERENCES accounts (id),
    -- The group's account in their personal ledger, whose balance is always their balance in the group.
    account_id         uuid    NOT NULL UNIQUE REFERENCES accounts (id),
    -- For shares of expenses whose category they have no category for.
    category_id        uuid    NOT NULL REFERENCES accounts (id),
    -- Where what they pay comes from, and what they are paid goes, unless they change it.
    payment_account_id uuid    NOT NULL REFERENCES accounts (id),
    enabled            boolean NOT NULL,
    PRIMARY KEY (user_id, group_id)
);

CREATE INDEX group_mirrors_group_id_idx ON group_mirrors (group_id) WHERE enabled;

-- One row per entry of a group's ledger and person, written when the mirror handles it, so an event
-- delivered twice, or late, never records it twice. Rows are written in the listener's transaction
-- together with the personal entry, or with nothing at all.
CREATE TABLE group_mirror_entries (
    group_entry_id    uuid    NOT NULL REFERENCES journal_entries (id),
    user_id           uuid    NOT NULL REFERENCES users (id),
    group_id          uuid    NOT NULL REFERENCES groups (id),
    -- What it is in the group; unknown for the entries counted in the opening balance, which the
    -- mirror never looked at one by one.
    type              text    CHECK (type IN ('EXPENSE', 'SETTLEMENT')),
    -- The personal entry that records it now; replaced when the person changes its account. Null
    -- when nothing was recorded for it on its own: it was already in the balance the group's account
    -- opened with, or an edit or a reversal of it arrived first.
    personal_entry_id uuid    UNIQUE REFERENCES journal_entries (id),
    -- In the balance the group's account opened with the last time the mirror was turned on, so a
    -- later correction of it only adjusts that balance.
    before_mirror     boolean NOT NULL,
    PRIMARY KEY (group_entry_id, user_id)
);

CREATE INDEX group_mirror_entries_user_group_idx ON group_mirror_entries (user_id, group_id);
