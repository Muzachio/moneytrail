package io.github.muzachio.moneytrail.shared.idempotency;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

/**
 * A response as it is stored and sent: the status, the headers the endpoint returned and the body
 * already serialized. The first response and every replay are written from this same value, so a
 * replay cannot differ from the original.
 */
// Never compared or used as a key, so the array's identity-based equals does not matter.
@SuppressWarnings("ArrayRecordComponent")
record StoredResponse(int status, Map<String, List<String>> headers, byte @Nullable [] body) {

    /**
     * Serializes the body with the application's {@code JsonMapper}, the one Spring MVC writes JSON
     * with. Idempotent endpoints answer with JSON or with no body.
     */
    static StoredResponse of(ResponseEntity<?> response, JsonMapper json) {
        HttpHeaders headers = HttpHeaders.copyOf(response.getHeaders());
        Object body = response.getBody();
        byte[] serialized = null;
        if (body != null) {
            serialized = json.writeValueAsBytes(body);
            if (headers.getContentType() == null) {
                headers.setContentType(MediaType.APPLICATION_JSON);
            }
        }
        Map<String, List<String>> headerValues = new LinkedHashMap<>();
        headers.forEach((name, values) -> headerValues.put(name, List.copyOf(values)));
        return new StoredResponse(response.getStatusCode().value(), headerValues, serialized);
    }

    /**
     * The body as bytes with its Content-Type set: Spring MVC then writes them unchanged instead of
     * serializing again.
     */
    ResponseEntity<byte[]> toResponseEntity() {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::addAll);
        return new ResponseEntity<>(body, httpHeaders, HttpStatusCode.valueOf(status));
    }
}
