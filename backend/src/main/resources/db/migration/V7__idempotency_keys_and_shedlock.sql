-- Idempotency-Key (docs/architecture.md "Idempotency flow"): the response of each money-moving
-- request that succeeded, so a retry gets it again instead of recording the movement twice. A row
-- is inserted in the same transaction as the request's business writes, so it exists exactly when
-- they were committed. Rows are never updated; the worker deletes them after 24 hours.
CREATE TABLE idempotency_keys (
    -- Keys belong to a user, so two users sending the same key never see each other's responses.
    -- The responses may contain personal data, so they go when their user goes.
    user_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    key              text        NOT NULL CHECK (length(key) BETWEEN 1 AND 255),
    -- SHA-256 of the method, the path with its query and the body: the same key sent with a
    -- different request is refused instead of replaying a response that does not belong to it.
    request_hash     bytea       NOT NULL CHECK (length(request_hash) = 32),
    response_status  smallint    NOT NULL CHECK (response_status BETWEEN 100 AND 599),
    -- The headers the endpoint returned, as {"Name": ["value", ...]}, e.g. Location.
    response_headers jsonb       NOT NULL,
    -- Exactly the bytes sent the first time, so a replay is identical to it; NULL without a body.
    response_body    bytea,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, key)
);

-- For the daily cleanup, which deletes the rows older than 24 hours.
CREATE INDEX idempotency_keys_created_at_idx ON idempotency_keys (created_at);

-- ShedLock: one row per scheduled job, holding who runs it and until when, so a job runs on one
-- instance only (ADR-0005). The definition for PostgreSQL from ShedLock's README. The times are UTC
-- from the database's clock (usingDbTime() in SchedulingConfiguration), so the machines' clocks do
-- not matter.
CREATE TABLE shedlock (
    name       varchar(64)  NOT NULL PRIMARY KEY,
    lock_until timestamp    NOT NULL,
    locked_at  timestamp    NOT NULL,
    locked_by  varchar(255) NOT NULL
);
