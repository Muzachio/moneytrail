package io.github.muzachio.moneytrail.groups;

import java.time.Instant;
import java.util.UUID;

/**
 * A member left a group, with a balance of zero. Published in the transaction that records it, like
 * {@link GroupCreated}.
 */
public record MemberLeft(UUID groupId, UUID userId, Instant leftAt) {}
