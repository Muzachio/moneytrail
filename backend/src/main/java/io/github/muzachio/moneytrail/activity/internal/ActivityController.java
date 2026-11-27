package io.github.muzachio.moneytrail.activity.internal;

import io.github.muzachio.moneytrail.activity.internal.ActivityStore.Stored;
import io.github.muzachio.moneytrail.groups.ExpenseCategory;
import io.github.muzachio.moneytrail.identity.SignedIn;
import io.github.muzachio.moneytrail.identity.UserProfile;
import io.github.muzachio.moneytrail.shared.Money;
import io.github.muzachio.moneytrail.shared.api.GroupActivityApi;
import io.github.muzachio.moneytrail.shared.api.model.ActivityItem;
import io.github.muzachio.moneytrail.shared.api.model.ActivityPage;
import io.github.muzachio.moneytrail.shared.api.model.GroupExpenseCategory;
import io.github.muzachio.moneytrail.shared.api.model.Profile;
import java.net.URI;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ActivityController implements GroupActivityApi {

    private final GroupActivity activity;

    ActivityController(GroupActivity activity) {
        this.activity = activity;
    }

    /** The schema has already checked the cursor's length and the limit's range. */
    @Override
    public ResponseEntity<ActivityPage> listGroupActivity(UUID id, @Nullable String cursor, Integer limit) {
        GroupActivity.Page page = activity.page(SignedIn.userId(), id, cursor, limit);
        ActivityPage body = new ActivityPage(
                page.items().stream().map(ActivityController::toApi).toList(),
                page.people().stream().map(ActivityController::toApi).toList());
        String nextCursor = page.nextCursor();
        if (nextCursor != null) {
            body.nextCursor(nextCursor);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Every kind but the member ones is about an expense or a payment, whose id the client opens; a
     * member's id is already {@code actorId}, and the group's is in the path.
     */
    private static ActivityItem toApi(Stored stored) {
        Activity item = stored.activity();
        ActivityItem body = new ActivityItem(
                        stored.id(),
                        io.github.muzachio.moneytrail.shared.api.model.ActivityKind.valueOf(
                                item.kind().name()),
                        item.actorId(),
                        item.occurredAt().atOffset(ZoneOffset.UTC))
                .groupName(item.groupName())
                .description(item.description())
                .category(toApi(item.category()))
                .amount(toApi(item.amount()))
                .previousDescription(item.previousDescription())
                .previousCategory(toApi(item.previousCategory()))
                .previousAmount(toApi(item.previousAmount()))
                .paidBy(item.paidBy())
                .from(item.from())
                .to(item.to())
                .occurredOn(item.occurredOn());
        return switch (item.kind()) {
            case GROUP_CREATED, MEMBER_JOINED, MEMBER_LEFT -> body;
            case EXPENSE_ADDED,
                    EXPENSE_EDITED,
                    EXPENSE_REVERSED,
                    SETTLEMENT_RECORDED,
                    SETTLEMENT_EDITED,
                    SETTLEMENT_REVERSED -> body.subjectId(item.subjectId());
        };
    }

    private static @Nullable GroupExpenseCategory toApi(@Nullable ExpenseCategory category) {
        return category == null ? null : GroupExpenseCategory.fromValue(category.key());
    }

    private static io.github.muzachio.moneytrail.shared.api.model.@Nullable Money toApi(@Nullable Money money) {
        return money == null
                ? null
                : new io.github.muzachio.moneytrail.shared.api.model.Money(
                        money.minor(), money.currency().getCurrencyCode());
    }

    private static Profile toApi(UserProfile profile) {
        Profile body = new Profile(profile.id(), profile.name());
        String avatarUrl = profile.avatarUrl();
        if (avatarUrl != null) {
            body.avatarUrl(URI.create(avatarUrl));
        }
        return body;
    }
}
