package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.MethodMode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Traces, metrics and logs leave the app over OTLP/HTTP, the protocol Grafana Cloud and the local
 * grafana/otel-lgtm stack both receive, with the Authorization header Grafana Cloud requires. A
 * tiny in-process HTTP server stands in for the collector, so the test needs no extra container.
 */
@AutoConfigureMockMvc
@AutoConfigureTracing
@AutoConfigureMetrics
class OtlpExportTests extends PostgresIntegrationTest {

    private static final String AUTHORIZATION = "Basic dGVzdC1pbnN0YW5jZTp0ZXN0LXRva2Vu";
    private static final Logger log = LoggerFactory.getLogger(OtlpExportTests.class);

    private static final Set<String> receivedPaths = ConcurrentHashMap.newKeySet();
    private static final Set<String> receivedAuthorizations = ConcurrentHashMap.newKeySet();
    private static final HttpServer collector = startCollector();

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @Autowired
    private SdkLoggerProvider loggerProvider;

    @DynamicPropertySource
    static void pointExportersAtCollector(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + collector.getAddress().getPort();
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> base + "/v1/traces");
        registry.add("management.opentelemetry.tracing.export.otlp.headers.Authorization", () -> AUTHORIZATION);
        registry.add("management.opentelemetry.logging.export.otlp.endpoint", () -> base + "/v1/logs");
        registry.add("management.opentelemetry.logging.export.otlp.headers.Authorization", () -> AUTHORIZATION);
        registry.add("management.otlp.metrics.export.enabled", () -> "true");
        registry.add("management.otlp.metrics.export.url", () -> base + "/v1/metrics");
        registry.add("management.otlp.metrics.export.headers.Authorization", () -> AUTHORIZATION);
        registry.add("management.otlp.metrics.export.step", () -> "1s");
    }

    @AfterAll
    static void stopCollector() {
        collector.stop(0);
    }

    // The context publishes a last batch of metrics when it closes, so it must close while the
    // collector is still running, not at the end of the JVM.
    @Test
    @DirtiesContext(methodMode = MethodMode.AFTER_METHOD)
    void exportsTracesMetricsAndLogsWithTheAuthorizationHeader() {
        assertThat(mvc.get().uri("/v1/health")).hasStatusOk();
        log.info("OTLP log export check");
        tracerProvider.forceFlush();
        loggerProvider.forceFlush();

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(receivedPaths).contains("/v1/traces", "/v1/metrics", "/v1/logs"));
        assertThat(receivedAuthorizations).containsOnly(AUTHORIZATION);
    }

    private static HttpServer startCollector() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                receivedPaths.add(exchange.getRequestURI().getPath());
                receivedAuthorizations.add(
                        String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
