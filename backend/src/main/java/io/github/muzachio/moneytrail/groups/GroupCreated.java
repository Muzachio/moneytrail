package io.github.muzachio.moneytrail.groups;

import java.time.Instant;
import java.util.UUID;

/**
 * Someone created a group, and is its first member. Published in the transaction that creates it,
 * so a listener through the outbox, such as the activity feed (#90), hears of it if and only if the
 * group exists. It carries what such a listener shows, so it needs no lookup.
 */
public record GroupCreated(UUID groupId, String name, UUID createdBy, Instant createdAt) {}
