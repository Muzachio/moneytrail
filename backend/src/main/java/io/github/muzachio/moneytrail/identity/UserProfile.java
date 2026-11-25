package io.github.muzachio.moneytrail.identity;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What other people may see of a user, such as the other members of a group: the name and photo
 * from their Google account. Never the email, which stays between the user and the app.
 */
public record UserProfile(UUID id, String name, @Nullable String avatarUrl) {}
