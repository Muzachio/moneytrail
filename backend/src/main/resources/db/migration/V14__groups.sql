-- Groups of friends who share expenses (ADR-0012). Only the groups module writes these tables. Each
-- group has a ledger of its own, opened in the same transaction as the group, with one GROUP_MEMBER
-- account per member; who owns which account is kept here, since the ledger knows nothing about
-- people.

CREATE TABLE groups (
    -- Taken before the row exists, because the group's ledger is opened for this id first.
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    name        text        NOT NULL,
    -- Every amount of the group is in this currency, so it never changes (ADR-0012).
    currency    char(3)     NOT NULL,
    ledger_id   uuid        NOT NULL UNIQUE REFERENCES ledgers (id),
    created_by  uuid        NOT NULL REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- Archived only with every balance at zero; an archived group takes no new expenses or payments.
    archived_at timestamptz
);

-- A member who leaves keeps their row, with left_at set, so their account and their part in past
-- expenses stay, and joining again gives them the same account back.
CREATE TABLE group_members (
    group_id   uuid        NOT NULL REFERENCES groups (id),
    user_id    uuid        NOT NULL REFERENCES users (id),
    account_id uuid        NOT NULL UNIQUE REFERENCES accounts (id),
    joined_at  timestamptz NOT NULL DEFAULT now(),
    left_at    timestamptz,
    PRIMARY KEY (group_id, user_id)
);

-- The groups of a user (GET /v1/groups). The primary key already serves lookups by group.
CREATE INDEX group_members_user_id_idx ON group_members (user_id);
