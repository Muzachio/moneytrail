package io.github.muzachio.moneytrail.personal;

import org.jspecify.annotations.Nullable;

/**
 * How the API names and draws a category. A default category has its {@code key}, which the app
 * translates, and a {@code name} only once the user renames it; a category of the user's own only
 * has its name. The app shows {@code name}, or else its translation of {@code key}.
 *
 * @param icon the name of its icon in the API's {@code CategoryIcon} list: the icon of its key for
 *     a default category, the one picked for a category of the user's own
 */
public record CategoryLabel(@Nullable String key, @Nullable String name, String icon) {}
