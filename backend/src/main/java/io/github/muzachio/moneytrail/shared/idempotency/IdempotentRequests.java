package io.github.muzachio.moneytrail.shared.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs each idempotent endpoint (see {@link IdempotentEndpoints}) at most once per user and
 * {@code Idempotency-Key}. Around the controller method, in one transaction:
 *
 * <ol>
 *   <li>take the key's lock without waiting; if the first request with the key still holds it,
 *       answer 409;
 *   <li>if the key has a stored response: answer 422 when this request is not the same as the one
 *       that stored it, or replay that response;
 *   <li>otherwise call the controller, whose services join this transaction, and store its
 *       response.
 * </ol>
 *
 * <p>The business writes and the stored response are therefore committed together, before Spring
 * MVC writes a single byte of the response. A request that throws, or whose commit fails, leaves
 * neither behind and can be retried with the same key. Services must not open a transaction of
 * their own ({@code REQUIRES_NEW}): it would commit apart from the stored response.
 */
@Component
class IdempotentRequests implements MethodInterceptor {

    /** Request attribute with the raw body, which {@link IdempotentRequestBodies} keeps. */
    static final String BODY_ATTRIBUTE = IdempotentRequests.class.getName() + ".body";

    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyKeys keys;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    IdempotentRequests(IdempotencyKeys keys, PlatformTransactionManager transactionManager, JsonMapper json) {
        this.keys = keys;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        HttpServletRequest request = currentRequest();
        String key = keyOf(request);
        UUID userId = userOf(request);
        byte[] requestHash = fingerprint(request);
        try {
            return Objects.requireNonNull(
                    transactions.execute(status -> respond(invocation, userId, key, requestHash)));
        } catch (CheckedHandlerException e) {
            throw e.handlerException;
        }
    }

    private ResponseEntity<byte[]> respond(MethodInvocation invocation, UUID userId, String key, byte[] requestHash) {
        if (!keys.tryLock(userId, key)) {
            throw IdempotencyKeyException.inUse();
        }
        Optional<IdempotencyKeys.Recorded> recorded = keys.find(userId, key);
        if (recorded.isPresent()) {
            if (!Arrays.equals(recorded.get().requestHash(), requestHash)) {
                throw IdempotencyKeyException.reused();
            }
            return recorded.get().response().toResponseEntity();
        }
        StoredResponse response = StoredResponse.of(proceed(invocation), json);
        keys.save(userId, key, requestHash, response);
        return response.toResponseEntity();
    }

    private static ResponseEntity<?> proceed(MethodInvocation invocation) {
        @Nullable Object result;
        try {
            result = invocation.proceed();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new CheckedHandlerException(e);
        }
        if (result instanceof ResponseEntity<?> response) {
            return response;
        }
        throw new IllegalStateException(invocation.getMethod() + " takes an " + IdempotentEndpoints.HEADER
                + " header, so it must return a ResponseEntity");
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        throw new IllegalStateException(IdempotentEndpoints.HEADER + " is only handled in an HTTP request");
    }

    private static String keyOf(HttpServletRequest request) {
        String key = request.getHeader(IdempotentEndpoints.HEADER);
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw IdempotencyKeyException.invalid();
        }
        return key;
    }

    /** The principal's name is the internal user id (identity's SignedInUser). */
    private static UUID userOf(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal == null) {
            throw new IllegalStateException(IdempotentEndpoints.HEADER + " needs a signed-in user");
        }
        return UUID.fromString(principal.getName());
    }

    /**
     * SHA-256 of the method, the path with its query and the raw body: what makes a retry the same
     * request. The path counts because a key reused on another resource, such as reversing another
     * transaction with the same empty body, is a different request.
     */
    private static byte[] fingerprint(HttpServletRequest request) {
        MessageDigest sha256 = IdempotencyKeys.sha256();
        String query = request.getQueryString();
        String target = request.getMethod() + " " + request.getRequestURI() + (query == null ? "" : "?" + query) + "\n";
        sha256.update(target.getBytes(StandardCharsets.UTF_8));
        if (request.getAttribute(BODY_ATTRIBUTE) instanceof byte[] body) {
            sha256.update(body);
        }
        return sha256.digest();
    }

    /** Carries a checked exception of the controller out of the transaction, rolling it back. */
    private static final class CheckedHandlerException extends RuntimeException {

        private final Throwable handlerException;

        CheckedHandlerException(Throwable handlerException) {
            super(handlerException);
            this.handlerException = handlerException;
        }
    }
}
