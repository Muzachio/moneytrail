package io.github.muzachio.moneytrail.activity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.jayway.jsonpath.JsonPath;
import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * GET /v1/groups/{id}/activity as in api/openapi.yaml, with the changes made through the API, so the
 * events come from the groups module itself. The items are written after each change commits, so the
 * tests wait for them.
 */
@AutoConfigureMockMvc
class GroupActivityApiTests extends PostgresIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    private UUID ana;
    private UUID joao;
    private UUID group;

    @BeforeEach
    void anasGroupWhichJoaoJoins() {
        ana = newUser("Ana", "https://example.com/ana.png");
        joao = newUser("João", null);
        group = newGroup(ana);
        join(joao);
    }

    @Test
    void listsWhatHappenedNewestFirstWithThePeopleItNames() {
        UUID dinner = recordExpense(ana, 3000, "Dinner");
        UUID corrected = editExpense(joao, dinner, 3600, "Dinner and wine");
        recordSettlement(joao, 1800, joao, ana);

        String body = awaitItems(ana, 5);

        assertThat(JsonPath.<List<String>>read(body, "$.items[*].kind"))
                .containsExactly(
                        "SETTLEMENT_RECORDED", "EXPENSE_EDITED", "EXPENSE_ADDED", "MEMBER_JOINED", "GROUP_CREATED");

        Map<String, Object> edited = JsonPath.read(body, "$.items[1]");
        assertThat(edited)
                .containsEntry("actorId", joao.toString())
                .containsEntry("subjectId", corrected.toString())
                .containsEntry("description", "Dinner and wine")
                .containsEntry("category", "leisure")
                .containsEntry("amount", Map.of("amountMinor", 3600, "currency", "EUR"))
                .containsEntry("previousDescription", "Dinner")
                .containsEntry("previousCategory", "food")
                .containsEntry("previousAmount", Map.of("amountMinor", 3000, "currency", "EUR"))
                .containsEntry("paidBy", ana.toString())
                .containsEntry("occurredOn", DAY.toString())
                .containsKey("occurredAt")
                .doesNotContainKeys("from", "to", "groupName");

        Map<String, Object> settlement = JsonPath.read(body, "$.items[0]");
        assertThat(settlement)
                .containsEntry("actorId", joao.toString())
                .containsEntry("from", joao.toString())
                .containsEntry("to", ana.toString())
                .containsEntry("amount", Map.of("amountMinor", 1800, "currency", "EUR"))
                .doesNotContainKeys("description", "paidBy", "previousAmount");

        Map<String, Object> created = JsonPath.read(body, "$.items[4]");
        assertThat(created)
                .containsEntry("actorId", ana.toString())
                .containsEntry("groupName", "Porto weekend")
                .as("a group's or a member's item points at nothing to open")
                .doesNotContainKeys("subjectId", "amount");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.items[3]"))
                .containsEntry("actorId", joao.toString())
                .doesNotContainKey("subjectId");

        assertThat(JsonPath.<List<Map<String, Object>>>read(body, "$.people"))
                .containsExactlyInAnyOrder(
                        Map.of("userId", ana.toString(), "name", "Ana", "avatarUrl", "https://example.com/ana.png"),
                        Map.of("userId", joao.toString(), "name", "João"));
        assertThat(body).as("never an email").doesNotContain("@example.com\"").doesNotContain("activity-");
        assertThat(JsonPath.<List<Object>>read(body, "$..nextCursor")).isEmpty();
    }

    /** Keyset pagination: each item once, newest first, across pages. */
    @Test
    void pagesThroughTheFeedWithItsCursor() {
        recordExpense(ana, 3000, "Dinner");
        recordExpense(joao, 1200, "Taxi");
        recordSettlement(joao, 600, joao, ana);
        String whole = awaitItems(ana, 5);

        List<String> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String page = body(assertOk(feed(ana, 2, cursor)));
            List<String> pageIds = JsonPath.read(page, "$.items[*].id");
            assertThat(pageIds).hasSizeBetween(1, 2);
            ids.addAll(pageIds);
            List<String> next = JsonPath.read(page, "$..nextCursor");
            cursor = next.isEmpty() ? null : next.getFirst();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(ids).containsExactlyElementsOf(JsonPath.<List<String>>read(whole, "$.items[*].id"));
    }

    @Test
    void aCursorTheApiDidNotMakeIsABadRequest() {
        assertProblem(feed(ana, 10, "bm9uc2Vuc2U"), HttpStatus.BAD_REQUEST, "bad_request");
    }

    @Test
    void someoneWhoLeftAndSomeoneWhoNeverJoinedGetNotFound() {
        UUID bea = newUser("Bea", null);
        assertProblem(feed(bea, 10, null), HttpStatus.NOT_FOUND, "group_not_found");

        assertThat(mvc.post()
                        .uri("/v1/groups/" + group + "/leave")
                        .with(user(joao.toString()))
                        .with(csrf())
                        .exchange())
                .hasStatus(HttpStatus.NO_CONTENT);
        assertProblem(feed(joao, 10, null), HttpStatus.NOT_FOUND, "group_not_found");
    }

    /** Waits for the listener to have written {@code count} items, and returns that page. */
    private String awaitItems(UUID userId, int count) {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> JsonPath.<List<Object>>read(body(feed(userId, 100, null)), "$.items")
                                .size()
                        == count);
        return body(assertOk(feed(userId, 100, null)));
    }

    private MvcTestResult feed(UUID userId, int limit, @Nullable String cursor) {
        String query = "?limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor);
        return mvc.get()
                .uri("/v1/groups/" + group + "/activity" + query)
                .with(user(userId.toString()))
                .exchange();
    }

    private UUID newGroup(UUID by) {
        MvcTestResult created = mvc.post()
                .uri("/v1/groups")
                .with(user(by.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Porto weekend\"}")
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(JsonPath.read(body(created), "$.id"));
    }

    private void join(UUID userId) {
        MvcTestResult link = mvc.post()
                .uri("/v1/groups/" + group + "/invites")
                .with(user(ana.toString()))
                .with(csrf())
                .exchange();
        assertThat(link).hasStatus(HttpStatus.CREATED);
        assertOk(mvc.post()
                .uri("/v1/invites/accept")
                .with(user(userId.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"%s\"}".formatted(JsonPath.<String>read(body(link), "$.token")))
                .exchange());
    }

    /** Paid by {@code by}, split equally between Ana and João. */
    private UUID recordExpense(UUID by, long amountMinor, String description) {
        MvcTestResult recorded = mvc.post()
                .uri("/v1/groups/" + group + "/expenses")
                .with(user(by.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(expense(amountMinor, description, "food", by))
                .exchange();
        assertThat(recorded).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(JsonPath.read(body(recorded), "$.id"));
    }

    /** Ana paid, for leisure; returns the id of the corrected version. */
    private UUID editExpense(UUID by, UUID expense, long amountMinor, String description) {
        MvcTestResult edited = mvc.post()
                .uri("/v1/groups/" + group + "/expenses/" + expense + "/reverse")
                .with(user(by.toString()))
                .with(csrf())
                .header("Idempotency-Key", newKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"replacement\": %s}".formatted(expense(amountMinor, description, "leisure", ana)))
                .exchange();
        return UUID.fromString(JsonPath.read(body(assertOk(edited)), "$.currentId"));
    }

    private void recordSettlement(UUID by, long amountMinor, UUID from, UUID to) {
        assertThat(mvc.post()
                        .uri("/v1/groups/" + group + "/settlements")
                        .with(user(by.toString()))
                        .with(csrf())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": {"amountMinor": %d, "currency": "EUR"}, "occurredOn": "%s",
                                 "from": "%s", "to": "%s"}""".formatted(amountMinor, DAY, from, to))
                        .exchange())
                .hasStatus(HttpStatus.CREATED);
    }

    private String expense(long amountMinor, String description, String category, UUID paidBy) {
        return """
                {"amount": {"amountMinor": %d, "currency": "EUR"}, "occurredOn": "%s", "description": "%s",
                 "category": "%s", "paidBy": "%s",
                 "split": {"method": "EQUALLY", "members": [{"userId": "%s"}, {"userId": "%s"}]}}""".formatted(amountMinor, DAY, description, category, paidBy, ana, joao);
    }

    private UUID newUser(String name, @Nullable String avatarUrl) {
        return jdbc.sql("INSERT INTO users (email, name, avatar_url) VALUES (:email, :name, :avatar) RETURNING id")
                .param("email", "activity-" + UUID.randomUUID() + "@example.com")
                .param("name", name)
                .param("avatar", avatarUrl)
                .query(UUID.class)
                .single();
    }

    private static MvcTestResult assertOk(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        return result;
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result)
                .hasStatus(status)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo(code);
    }
}
