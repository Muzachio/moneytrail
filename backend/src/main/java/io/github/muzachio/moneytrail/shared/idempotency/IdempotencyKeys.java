package io.github.muzachio.moneytrail.shared.idempotency;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The {@code idempotency_keys} table (V7), and the lock that keeps one request per key at a time. */
@Repository
class IdempotencyKeys {

    /** What the first request with a key left: its fingerprint and its response. */
    @SuppressWarnings("ArrayRecordComponent") // never compared; the hash is checked with Arrays.equals
    record Recorded(byte[] requestHash, StoredResponse response) {}

    private static final TypeReference<Map<String, List<String>>> HEADERS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    IdempotencyKeys(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Takes the key's lock for the rest of the current transaction, without waiting: {@code false}
     * means another transaction, the first request still running, holds it. A transaction-level
     * advisory lock is released by the commit or rollback, including when the process dies and
     * Postgres closes its connection, so a key is never left locked.
     */
    boolean tryLock(UUID userId, String key) {
        return jdbc.sql("SELECT pg_try_advisory_xact_lock(:lockId)")
                .param("lockId", lockId(userId, key))
                .query(Boolean.class)
                .single();
    }

    Optional<Recorded> find(UUID userId, String key) {
        return jdbc.sql("""
                        SELECT request_hash, response_status, response_headers::text AS response_headers, response_body
                        FROM idempotency_keys
                        WHERE user_id = :userId AND key = :key""")
                .param("userId", userId)
                .param("key", key)
                .query((rs, rowNum) -> new Recorded(
                        rs.getBytes("request_hash"),
                        new StoredResponse(
                                rs.getInt("response_status"),
                                json.readValue(rs.getString("response_headers"), HEADERS),
                                rs.getBytes("response_body"))))
                .optional();
    }

    void save(UUID userId, String key, byte[] requestHash, StoredResponse response) {
        jdbc.sql("""
                        INSERT INTO idempotency_keys
                            (user_id, key, request_hash, response_status, response_headers, response_body)
                        VALUES (:userId, :key, :requestHash, :status, CAST(:headers AS jsonb), :body)""")
                .param("userId", userId)
                .param("key", key)
                .param("requestHash", requestHash)
                .param("status", response.status())
                .param("headers", json.writeValueAsString(response.headers()))
                // With its type: a NULL body (no content) must still bind as bytea.
                .param("body", response.body(), Types.BINARY)
                .update();
    }

    /** Uses the database's clock, the one that set {@code created_at}. */
    int deleteOlderThan(Duration age) {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < now() - CAST(:age AS interval)")
                .param("age", age.toString())
                .update();
    }

    /**
     * Advisory locks are numbered with one {@code bigint}: the first 8 bytes of a SHA-256 of user
     * and key. Two pairs sharing a number is very unlikely, and would only make one of two
     * simultaneous requests answer 409 and be retried.
     */
    static long lockId(UUID userId, String key) {
        return ByteBuffer.wrap(sha256().digest((userId + "\n" + key).getBytes(StandardCharsets.UTF_8)))
                .getLong();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime must provide SHA-256.
            throw new IllegalStateException(e);
        }
    }
}
