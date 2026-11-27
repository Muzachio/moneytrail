package io.github.muzachio.moneytrail.activity.internal;

import io.github.muzachio.moneytrail.groups.ExpenseCategory;
import io.github.muzachio.moneytrail.shared.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code activity_items} table (V20), which only this module writes. */
@Repository
class ActivityStore {

    /** An item of a feed, with the id the table gave it. */
    record Stored(UUID id, Activity activity) {}

    /** Where a page ended: the last item's time and id, which order the feed. */
    record Position(Instant occurredAt, UUID id) {}

    private static final String COLUMNS = """
            id, group_id, kind, actor_id, occurred_at, subject_id, group_name, description, category,
            amount_minor, currency, previous_description, previous_category, previous_amount_minor, paid_by,
            from_user_id, to_user_id, occurred_on""";

    private final JdbcClient jdbc;

    ActivityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds the item unless the feed has it already, and says whether it did. An event delivered again
     * (the outbox delivers at least once) hits the unique key of V20 and adds nothing, also when two
     * deliveries run at the same time: the second insert waits for the first and then does nothing.
     */
    boolean add(Activity activity) {
        Money amount = activity.amount();
        Money previousAmount = activity.previousAmount();
        ExpenseCategory category = activity.category();
        ExpenseCategory previousCategory = activity.previousCategory();
        return jdbc.sql("""
                        INSERT INTO activity_items (
                            group_id, kind, actor_id, occurred_at, subject_id, group_name, description,
                            category, amount_minor, currency, previous_description, previous_category,
                            previous_amount_minor, paid_by, from_user_id, to_user_id, occurred_on)
                        VALUES (
                            :groupId, :kind, :actorId, :occurredAt, :subjectId, :groupName, :description,
                            :category, :amountMinor, :currency, :previousDescription, :previousCategory,
                            :previousAmountMinor, :paidBy, :from, :to, :occurredOn)
                        ON CONFLICT ON CONSTRAINT activity_items_event_key DO NOTHING""")
                        .param("groupId", activity.groupId())
                        .param("kind", activity.kind().name())
                        .param("actorId", activity.actorId())
                        .param("occurredAt", OffsetDateTime.ofInstant(activity.occurredAt(), ZoneOffset.UTC))
                        .param("subjectId", activity.subjectId())
                        .param("groupName", activity.groupName())
                        .param("description", activity.description())
                        .param("category", category == null ? null : category.key())
                        .param("amountMinor", amount == null ? null : amount.minor())
                        .param(
                                "currency",
                                amount == null ? null : amount.currency().getCurrencyCode())
                        .param("previousDescription", activity.previousDescription())
                        .param("previousCategory", previousCategory == null ? null : previousCategory.key())
                        .param("previousAmountMinor", previousAmount == null ? null : previousAmount.minor())
                        .param("paidBy", activity.paidBy())
                        .param("from", activity.from())
                        .param("to", activity.to())
                        .param("occurredOn", activity.occurredOn())
                        .update()
                == 1;
    }

    /** At most {@code limit} items of the group's feed after {@code after}, newest first. */
    List<Stored> page(UUID groupId, @Nullable Position after, int limit) {
        if (after == null) {
            return jdbc.sql("""
                            SELECT %s FROM activity_items
                            WHERE group_id = :groupId
                            ORDER BY occurred_at DESC, id DESC
                            LIMIT :limit""".formatted(COLUMNS))
                    .param("groupId", groupId)
                    .param("limit", limit)
                    .query(ActivityStore::stored)
                    .list();
        }
        return jdbc.sql("""
                        SELECT %s FROM activity_items
                        WHERE group_id = :groupId AND (occurred_at, id) < (:occurredAt, :id)
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT :limit""".formatted(COLUMNS))
                .param("groupId", groupId)
                .param("occurredAt", OffsetDateTime.ofInstant(after.occurredAt(), ZoneOffset.UTC))
                .param("id", after.id())
                .param("limit", limit)
                .query(ActivityStore::stored)
                .list();
    }

    private static Stored stored(ResultSet rs, int rowNum) throws SQLException {
        String currencyCode = rs.getString("currency");
        Currency currency = currencyCode == null ? null : Currency.getInstance(currencyCode);
        return new Stored(
                rs.getObject("id", UUID.class),
                new Activity(
                        rs.getObject("group_id", UUID.class),
                        ActivityKind.valueOf(rs.getString("kind")),
                        rs.getObject("actor_id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("subject_id", UUID.class),
                        rs.getString("group_name"),
                        rs.getString("description"),
                        category(rs.getString("category")),
                        money(rs.getObject("amount_minor", Long.class), currency),
                        rs.getString("previous_description"),
                        category(rs.getString("previous_category")),
                        money(rs.getObject("previous_amount_minor", Long.class), currency),
                        rs.getObject("paid_by", UUID.class),
                        rs.getObject("from_user_id", UUID.class),
                        rs.getObject("to_user_id", UUID.class),
                        rs.getObject("occurred_on", LocalDate.class)));
    }

    private static @Nullable ExpenseCategory category(@Nullable String key) {
        return key == null ? null : ExpenseCategory.ofKey(key);
    }

    private static @Nullable Money money(@Nullable Long minor, @Nullable Currency currency) {
        return minor == null || currency == null ? null : new Money(minor, currency);
    }
}
