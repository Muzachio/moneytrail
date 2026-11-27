package io.github.muzachio.moneytrail.activity.internal;

/**
 * One per event of the groups module. The names are the API's (ActivityKind in api/openapi.yaml) and
 * the database's (activity_items_kind_check in V20): a new kind needs all three.
 */
enum ActivityKind {
    GROUP_CREATED,
    MEMBER_JOINED,
    MEMBER_LEFT,
    EXPENSE_ADDED,
    EXPENSE_EDITED,
    EXPENSE_REVERSED,
    SETTLEMENT_RECORDED,
    SETTLEMENT_EDITED,
    SETTLEMENT_REVERSED
}
