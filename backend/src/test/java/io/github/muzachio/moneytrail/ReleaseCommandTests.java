package io.github.muzachio.moneytrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The deploy order of ADR-0009, run the way Fly.io runs it: the production jar in a JVM of its own,
 * with the arguments from fly.toml and the environment that Fly.io's secrets and [env] provide.
 * First the release_command migrates the database and exits; only then can the api process start.
 * Each test gets a database of its own.
 */
@Testcontainers
class ReleaseCommandTests {

    @Container
    private static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(PostgresTestcontainersConfiguration.POSTGRES);

    // The jar the image starts; build.gradle.kts builds it before the tests run.
    private static final Path BOOT_JAR = Path.of("build", "libs", "moneytrail.jar");

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    // The processes' working directory. Not backend/, where the app would read the local .env.
    @TempDir
    private Path workDir;

    @Test
    void releaseCommandMigratesAndExitsThenTheApiStarts() throws Exception {
        String database = createDatabase("deploy");

        // A web server would fail on this taken port; the release command must not start one. And
        // it must exit even when something keeps the JVM alive, as a scheduler will from M1 on.
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Run release = run(
                    database,
                    flyToml("release_command"),
                    "--server.port=" + taken.getLocalPort(),
                    "--spring.main.keep-alive=true");
            assertThat(release.exitCode()).as(release::output).isZero();
        }
        assertThat(flyway(database).info().pending()).isEmpty();

        int port = freePort();
        App api = start(database, flyToml("api"), "--server.port=" + port);
        try {
            await().atMost(TIMEOUT).untilAsserted(() -> {
                if (!api.process().isAlive()) {
                    throw new IllegalStateException("The api process exited:\n" + api.output());
                }
                assertThat(liveness(port)).isEqualTo(200);
            });
        } finally {
            api.stop();
        }
    }

    @Test
    void releaseCommandFailsWhenAMigrationFails() throws Exception {
        String database = createDatabase("broken");
        Path migrations = Files.createDirectory(workDir.resolve("broken-migrations"));
        Files.writeString(migrations.resolve("V9999__broken.sql"), "SELECT * FROM no_such_table;\n");
        String brokenLocation = "filesystem:" + migrations;

        Run release = run(
                database,
                flyToml("release_command"),
                "--spring.flyway.locations=classpath:db/migration," + brokenLocation);

        // A non-zero exit is what stops Fly.io's deploy. The migrations before the broken one stay
        // applied; the broken one was rolled back with its transaction.
        assertThat(release.exitCode()).as(release::output).isNotZero();
        assertThat(flyway(database, brokenLocation).info().pending())
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("9999");
    }

    @Test
    void apiRefusesToStartBeforeTheReleaseCommand() throws Exception {
        String database = createDatabase("unmigrated");

        Run api = run(database, flyToml("api"), "--server.port=" + freePort());

        assertThat(api.exitCode()).as(api::output).isNotZero();
        assertThat(api.output()).contains("FlywayValidateException");
        assertThat(flyway(database).info().applied()).isEmpty();
    }

    /** The arguments Fly.io passes to the image's {@code java -jar moneytrail.jar} (fly.toml). */
    private static List<String> flyToml(String key) throws IOException {
        Matcher value = Pattern.compile("(?m)^\\s*" + key + "\\s*=\\s*\"([^\"]*)\"")
                .matcher(Files.readString(Path.of("fly.toml")));
        assertThat(value.find()).as("%s in fly.toml", key).isTrue();
        return List.of(value.group(1).trim().split("\\s+"));
    }

    private Run run(String database, List<String> arguments, String... moreArguments)
            throws IOException, InterruptedException {
        App app = start(database, arguments, moreArguments);
        try {
            boolean exited = app.process().waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(exited)
                    .as(() -> "Still running after " + TIMEOUT.toSeconds() + " s:\n" + app.output())
                    .isTrue();
            return new Run(app.process().exitValue(), app.output());
        } finally {
            app.stop();
        }
    }

    private App start(String database, List<String> arguments, String... moreArguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElseThrow()); // the java running the tests
        command.addAll(List.of("-jar", BOOT_JAR.toAbsolutePath().toString()));
        command.addAll(arguments);
        // Only on this machine: no firewall prompt and nothing exposed on the network.
        command.add("--server.address=127.0.0.1");
        command.addAll(List.of(moreArguments));

        Path log = Files.createTempFile(workDir, "app-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        Map<String, String> env = builder.environment();
        env.put("SPRING_DATASOURCE_URL", jdbcUrl(database));
        env.put("SPRING_DATASOURCE_USERNAME", postgres.getUsername());
        env.put("SPRING_DATASOURCE_PASSWORD", postgres.getPassword());
        // The prod profile refuses to start without these (application-prod.yml). Invented values.
        env.put("GOOGLE_CLIENT_ID", "test-client-id");
        env.put("GOOGLE_CLIENT_SECRET", "test-client-secret");
        env.put("MONEYTRAIL_APP_URL", "https://app.example.com");
        env.put("MONEYTRAIL_COOKIE_DOMAIN", "example.com");
        // Port 9 (discard): nothing listens, so the exporters fail fast and harmlessly.
        env.put("GRAFANA_OTLP_ENDPOINT", "http://127.0.0.1:9/otlp");
        env.put("GRAFANA_OTLP_AUTHORIZATION", "Basic dGVzdDp0ZXN0");
        return new App(builder.start(), log);
    }

    private static String createDatabase(String name) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return name;
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
    }

    private static Flyway flyway(String database, String... extraLocations) {
        return Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(Stream.concat(Stream.of("classpath:db/migration"), Stream.of(extraLocations))
                        .toArray(String[]::new))
                .load();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** Fly.io's health check on the api machines (fly.toml). */
    private static int liveness(int port) throws InterruptedException {
        URI uri = URI.create("http://127.0.0.1:" + port + "/actuator/health/liveness");
        try (HttpClient http =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            return http.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        } catch (IOException notListeningYet) {
            return -1;
        }
    }

    private record Run(int exitCode, String output) {}

    private record App(Process process, Path log) {

        String output() {
            try {
                // Decoded leniently: with stdout in a file, the JVM writes in the platform charset.
                return new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void stop() throws InterruptedException {
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
        }
    }
}
