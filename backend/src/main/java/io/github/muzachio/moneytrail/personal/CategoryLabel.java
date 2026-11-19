package io.github.muzachio.moneytrail.personal;

import org.jspecify.annotations.Nullable;

/**
 * How the API names and draws a category: by a {@code key}, a name the app knows and shows in each
 * person's language, or by a {@code name} the person typed, which it shows as it is. A category has
 * one of the two, never both.
 *
 * @param key the name of its key in the API's {@code CategoryKey} list
 * @param icon the name of its icon in the API's {@code CategoryIcon} list: a default category's own,
 *     or the one picked for a category of the user's own
 */
public record CategoryLabel(@Nullable String key, @Nullable String name, String icon) {}
