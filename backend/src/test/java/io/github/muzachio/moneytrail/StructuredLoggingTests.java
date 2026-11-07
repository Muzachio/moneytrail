package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** The production log format: one JSON line per event, carrying the traceId and the MDC. */
@ActiveProfiles("prod")
// The prod profile refuses to start without the Google client (application-prod.yml).
@TestPropertySource(properties = {"GOOGLE_CLIENT_ID=test-client-id", "GOOGLE_CLIENT_SECRET=test-client-secret"})
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTests extends PostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StructuredLoggingTests.class);

    @Autowired
    private Tracer tracer;

    @Test
    void logLinesAreJsonWithTraceIdAndUserId(CapturedOutput output) {
        Span span = tracer.nextSpan().name("structured-logging-test").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span);
                MDC.MDCCloseable ignoredUserId = MDC.putCloseable("userId", "test-user-id")) {
            log.info("structured logging check");
        } finally {
            span.end();
        }

        String line = output.getOut()
                .lines()
                .filter(l -> l.contains("structured logging check"))
                .findFirst()
                .orElseThrow();
        assertThat(line).startsWith("{").endsWith("}");
        assertThat(line).contains(span.context().traceId());
        assertThat(line).contains("\"userId\":\"test-user-id\"");
    }
}
