-- Invite links to a group (ADR-0013). Only the groups module writes this table. The token itself is
-- never stored: it is 32 random bytes, so its SHA-256 is enough to find the link and useless to
-- anyone who reads the table.

CREATE TABLE group_invites (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    group_id   uuid        NOT NULL REFERENCES groups (id),
    token_hash bytea       NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    created_by uuid        NOT NULL REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    -- A link works for 7 days; nothing deletes it afterwards, so who made which link stays known.
    expires_at timestamptz NOT NULL DEFAULT now() + interval '7 days',
    revoked_at timestamptz
);

-- A group's links (GET /v1/groups/{id}/invites). The unique token_hash serves accepting one.
CREATE INDEX group_invites_group_id_idx ON group_invites (group_id);
