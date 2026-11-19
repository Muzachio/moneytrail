package io.github.muzachio.moneytrail.personal.internal;

import static java.util.stream.Collectors.toMap;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The known names of categories, in {@code category_keys} (V12), a table of this module's as
 * {@code category_icons} is. A category with a row is called by that name, which the app shows in
 * each person's language; one without a row by the name in its account, as the person typed it.
 */
@Repository
class CategoryKeys {

    private final JdbcClient jdbc;

    CategoryKeys(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The known names of these categories; one called by a name the person typed is left out. */
    Map<UUID, KnownName> of(Collection<UUID> categoryIds) {
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("SELECT category_id, key FROM category_keys WHERE category_id IN (:ids)")
                .param("ids", categoryIds)
                .query((row, rowNumber) -> Map.entry(
                        row.getObject("category_id", UUID.class),
                        KnownName.of(row.getString("key")).orElseThrow()))
                .stream()
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Calls a category by a known name, whether it had one before or not. */
    void give(UUID categoryId, KnownName name) {
        jdbc.sql("""
                        INSERT INTO category_keys (category_id, key) VALUES (:categoryId, :key)
                        ON CONFLICT (category_id) DO UPDATE SET key = EXCLUDED.key""").param("categoryId", categoryId).param("key", name.key()).update();
    }

    /** The category is called by a name the person typed from now on. */
    void remove(UUID categoryId) {
        jdbc.sql("DELETE FROM category_keys WHERE category_id = :categoryId")
                .param("categoryId", categoryId)
                .update();
    }
}
