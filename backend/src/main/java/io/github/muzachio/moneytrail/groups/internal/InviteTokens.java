package io.github.muzachio.moneytrail.groups.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Invite tokens (ADR-0013): 32 bytes from {@link SecureRandom}, written in base64url without padding
 * (43 characters), and the SHA-256 that is all the database keeps. A plain hash is enough: the token
 * is random, so unlike a password there is nothing to guess, and the hash can be looked up by index.
 */
@Component
class InviteTokens {

    static final int BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    String newToken() {
        byte[] token = new byte[BYTES];
        random.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /** The SHA-256 of the token as it is written, which is what the database keeps. */
    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }
}
