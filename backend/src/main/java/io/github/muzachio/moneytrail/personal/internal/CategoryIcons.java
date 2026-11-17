package io.github.muzachio.moneytrail.personal.internal;

import static java.util.stream.Collectors.toMap;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The icons picked for the user's own categories, in {@code category_icons} (V11). It is a table of
 * this module's: a category is a ledger account, but how the app draws one is not the ledger's
 * business, so neither {@code accounts} nor {@code LedgerService} change for it.
 */
@Repository
class CategoryIcons {

    private final JdbcClient jdbc;

    CategoryIcons(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The icons picked for these categories; one that has none picked is left out. */
    Map<UUID, Icon> of(Collection<UUID> categoryIds) {
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("SELECT category_id, icon FROM category_icons WHERE category_id IN (:ids)")
                .param("ids", categoryIds)
                .query((row, rowNumber) ->
                        Map.entry(row.getObject("category_id", UUID.class), Icon.of(row.getString("icon"))))
                .stream()
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Picks the icon of a category, whether it had one before or not. */
    void pick(UUID categoryId, Icon icon) {
        jdbc.sql("""
                        INSERT INTO category_icons (category_id, icon) VALUES (:categoryId, :icon)
                        ON CONFLICT (category_id) DO UPDATE SET icon = EXCLUDED.icon""")
                .param("categoryId", categoryId)
                .param("icon", icon.value())
                .update();
    }
}
