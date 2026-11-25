package io.github.muzachio.moneytrail.groups;

import java.time.Instant;
import java.util.UUID;

/**
 * Someone joined a group through an invite link, the first time or again after leaving. Published
 * in the transaction that records it, like {@link GroupCreated}.
 */
public record MemberJoined(UUID groupId, UUID userId, Instant joinedAt) {}
