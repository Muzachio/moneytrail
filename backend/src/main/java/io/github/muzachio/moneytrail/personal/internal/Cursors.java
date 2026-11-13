package io.github.muzachio.moneytrail.personal.internal;

import io.github.muzachio.moneytrail.ledger.EntryPosition;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * The transactions list's cursor: the last transaction's day and id, base64url-encoded so that
 * clients treat it as an opaque token, and its format can change without breaking them.
 */
final class Cursors {

    private static final char SEPARATOR = '_';

    private Cursors() {}

    static String encode(EntryPosition position) {
        String plain = position.occurredOn().toString() + SEPARATOR + position.entryId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** A cursor that this class did not make, or that was changed, is a 400. */
    static EntryPosition decode(String cursor) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = plain.indexOf(SEPARATOR);
            if (separator < 0) {
                throw invalid();
            }
            return new EntryPosition(
                    LocalDate.parse(plain.substring(0, separator)), UUID.fromString(plain.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static TransactionException invalid() {
        return TransactionException.badRequest("The cursor is not one that this API returned.");
    }
}
