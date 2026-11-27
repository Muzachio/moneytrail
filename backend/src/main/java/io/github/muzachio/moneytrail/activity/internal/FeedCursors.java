package io.github.muzachio.moneytrail.activity.internal;

import io.github.muzachio.moneytrail.activity.internal.ActivityStore.Position;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * The feed's cursor: the last item's time and id, base64url-encoded so that clients treat it as an
 * opaque token and its format can change without breaking them, like the expenses list's.
 */
final class FeedCursors {

    private static final char SEPARATOR = '_';

    private FeedCursors() {}

    static String encode(Position position) {
        String plain = position.occurredAt().toString() + SEPARATOR + position.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** A cursor that this class did not make, or that was changed, is a 400. */
    static Position decode(String cursor) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = plain.indexOf(SEPARATOR);
            if (separator < 0) {
                throw invalid();
            }
            return new Position(
                    Instant.parse(plain.substring(0, separator)), UUID.fromString(plain.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static ActivityException invalid() {
        return ActivityException.badRequest("The cursor is not one that this API returned.");
    }
}
