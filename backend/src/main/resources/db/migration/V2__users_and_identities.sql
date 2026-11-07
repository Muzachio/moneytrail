-- People who signed in. uuidv7() ids are time-ordered, so new rows land at the end of the index.
CREATE TABLE users (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    email      citext      NOT NULL UNIQUE,
    name       text        NOT NULL,
    avatar_url text,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Users are found by the provider's stable subject, never by email: an email can change or be
-- reused, the subject cannot (ADR-0007). One user may later link more providers.
CREATE TABLE identities (
    provider text NOT NULL,
    subject  text NOT NULL,
    user_id  uuid NOT NULL REFERENCES users (id),
    PRIMARY KEY (provider, subject)
);

CREATE INDEX identities_user_id_idx ON identities (user_id);
