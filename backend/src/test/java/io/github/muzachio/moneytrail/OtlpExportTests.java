package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Traces and metrics leave the app over OTLP/HTTP, the protocol Grafana Cloud and the local
 * grafana/otel-lgtm stack both receive. A tiny in-process HTTP server stands in for the collector,
 * so the test needs no extra container.
 */
@AutoConfigureMockMvc
@AutoConfigureTracing
@AutoConfigureMetrics
class OtlpExportTests extends PostgresIntegrationTest {

    private static final Set<String> receivedPaths = ConcurrentHashMap.newKeySet();
    private static final HttpServer collector = startCollector();

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @DynamicPropertySource
    static void pointExportersAtCollector(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + collector.getAddress().getPort();
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> base + "/v1/traces");
        registry.add("management.otlp.metrics.export.enabled", () -> "true");
        registry.add("management.otlp.metrics.export.url", () -> base + "/v1/metrics");
        registry.add("management.otlp.metrics.export.step", () -> "1s");
    }

    @AfterAll
    static void stopCollector() {
        collector.stop(0);
    }

    @Test
    void exportsTracesAndMetricsOfHttpRequests() {
        assertThat(mvc.get().uri("/v1/health")).hasStatusOk();
        tracerProvider.forceFlush();

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(receivedPaths).contains("/v1/traces", "/v1/metrics"));
    }

    private static HttpServer startCollector() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                receivedPaths.add(exchange.getRequestURI().getPath());
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
