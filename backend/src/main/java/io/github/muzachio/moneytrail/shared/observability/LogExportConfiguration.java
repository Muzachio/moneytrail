package io.github.muzachio.moneytrail.shared.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Copies every log event to OpenTelemetry, which exports it over OTLP when
 * {@code management.opentelemetry.logging.export.otlp.endpoint} is set (Grafana Cloud in
 * production). Spring Boot exports OpenTelemetry log records but leaves connecting Logback to the
 * application. Added in code rather than in a logback-spring.xml, so Spring Boot keeps its own
 * console setup, including the JSON format of the prod profile. Each record carries the traceId of
 * its request and the userId from the MDC.
 */
@Configuration(proxyBeanMethods = false)
class LogExportConfiguration {

    static final String APPENDER_NAME = "OPENTELEMETRY";

    @Bean
    InitializingBean openTelemetryLogAppender(OpenTelemetry openTelemetry) {
        return () -> {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            // Logback is configured once per JVM, while tests start several application contexts.
            if (root.getAppender(APPENDER_NAME) == null) {
                OpenTelemetryAppender appender = new OpenTelemetryAppender();
                appender.setName(APPENDER_NAME);
                appender.setContext(context);
                appender.setCaptureMdcAttributes("userId");
                appender.start();
                root.addAppender(appender);
            }
            OpenTelemetryAppender.install(openTelemetry);
        };
    }
}
