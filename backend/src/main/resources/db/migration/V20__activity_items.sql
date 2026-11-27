-- The activity feed of each group (#90, ADR-0014). Only the activity module writes this table, one
-- row per event of the groups module, after that event's transaction has committed. A row keeps what
-- the feed shows as it was at that moment, so reading the feed never asks the groups module, and an
-- edit adds a row instead of changing one.
CREATE TABLE activity_items (
    id                    uuid        PRIMARY KEY DEFAULT uuidv7(),
    group_id              uuid        NOT NULL REFERENCES groups (id),
    kind                  text        NOT NULL CONSTRAINT activity_items_kind_check CHECK (kind IN (
        'GROUP_CREATED', 'MEMBER_JOINED', 'MEMBER_LEFT', 'EXPENSE_ADDED', 'EXPENSE_EDITED',
        'EXPENSE_REVERSED', 'SETTLEMENT_RECORDED', 'SETTLEMENT_EDITED', 'SETTLEMENT_REVERSED')),
    -- Who did it and when, as the event says.
    actor_id              uuid        NOT NULL REFERENCES users (id),
    occurred_at           timestamptz NOT NULL,
    -- What it is about: the group, the member who joined or left, or the expense or payment in the
    -- version the event names.
    subject_id            uuid        NOT NULL,
    -- The rest depends on the kind (ActivityItem in api/openapi.yaml). Amounts are in minor units of
    -- the group's currency, never decimals.
    group_name            text,
    description           text,
    -- An expense's category, one of the keys V18 checks (GroupExpenseCategory in api/openapi.yaml).
    category              text
        CONSTRAINT activity_items_category_check
        CHECK (category IN ('housing', 'food', 'transport', 'bills', 'shopping', 'leisure', 'health', 'other')),
    amount_minor          bigint,
    currency              char(3),
    previous_description  text,
    previous_category     text
        CONSTRAINT activity_items_previous_category_check
        CHECK (previous_category IN ('housing', 'food', 'transport', 'bills', 'shopping', 'leisure', 'health', 'other')),
    previous_amount_minor bigint,
    paid_by               uuid        REFERENCES users (id),
    from_user_id          uuid        REFERENCES users (id),
    to_user_id            uuid        REFERENCES users (id),
    occurred_on           date,
    CONSTRAINT activity_items_amount_check CHECK ((amount_minor IS NULL) = (currency IS NULL)),
    -- The outbox delivers an event at least once: delivered again, it finds its row and adds nothing.
    -- A member who joins, leaves and joins again has an item for each time, told apart by when.
    CONSTRAINT activity_items_event_key UNIQUE (group_id, kind, subject_id, occurred_at)
);

-- A group's feed, newest first (GET /v1/groups/{id}/activity).
CREATE INDEX activity_items_feed_idx ON activity_items (group_id, occurred_at DESC, id DESC);
