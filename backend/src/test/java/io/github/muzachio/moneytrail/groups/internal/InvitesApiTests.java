package io.github.muzachio.moneytrail.groups.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.jayway.jsonpath.JsonPath;
import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.groups.MemberJoined;
import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The invite links of api/openapi.yaml (tag {@code invites}) and ADR-0013: made by members, kept as
 * a hash, listed without tokens, revoked, looked up and accepted, with one answer for every token that
 * opens no group, and no token in the output.
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class InvitesApiTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ConfigurableApplicationContext context;

    private UUID ana;
    private UUID joao;
    private UUID bea;
    private UUID flat;

    @BeforeEach
    void groupOfAnaAndJoao() {
        ana = newUser("Ana", "https://example.com/ana.png");
        joao = newUser("João", null);
        bea = newUser("Bea", null);
        flat = idOf(send(
                ana,
                mvc.post()
                        .uri("/v1/groups")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"name": "Flat"}""")));
        assertThat(accept(joao, tokenOf(createLink(ana, flat)))).hasStatusOk();
    }

    @Test
    void anyMemberMakesALinkShownOnceAndOnlyItsHashIsKept() throws Exception {
        MvcTestResult created = createLink(joao, flat);

        var body = assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson();
        body.extractingPath("$.token").asString().matches("^[A-Za-z0-9_-]{43}$");
        String token = tokenOf(created);
        UUID id = idOf(created);
        OffsetDateTime createdAt = OffsetDateTime.parse(JsonPath.read(content(created), "$.createdAt"));
        OffsetDateTime expiresAt = OffsetDateTime.parse(JsonPath.read(content(created), "$.expiresAt"));
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofDays(7));

        Map<String, @Nullable Object> row = jdbc.sql("SELECT * FROM group_invites WHERE id = :id")
                .param("id", id)
                .query()
                .singleRow();
        assertThat((byte[]) row.get("token_hash"))
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        assertThat(row.get("created_by")).isEqualTo(joao);
        assertThat(row.get("group_id")).isEqualTo(flat);
        assertThat(row.values())
                .as("no column keeps the token, as text or as its bytes")
                .noneMatch(value -> String.valueOf(value).contains(token)
                        || (value instanceof byte[] bytes
                                && HexFormat.of().formatHex(bytes).contains(token)));
    }

    @Test
    void anArchivedGroupTakesNoNewLinks() {
        UUID own = idOf(send(
                bea,
                mvc.post()
                        .uri("/v1/groups")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"name": "Mine"}""")));
        assertThat(send(
                        bea,
                        mvc.patch()
                                .uri("/v1/groups/" + own)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                        {"archived": true}""")))
                .hasStatusOk();

        assertProblem(createLink(bea, own), HttpStatus.CONFLICT, "group_archived");
    }

    @Test
    void theListShowsActiveLinksNewestFirstWithWhoMadeThemAndNoToken() {
        MvcTestResult first = createLink(ana, flat);
        MvcTestResult revoked = createLink(joao, flat);
        MvcTestResult expired = createLink(joao, flat);
        MvcTestResult last = createLink(joao, flat);
        assertThat(revoke(ana, flat, idOf(revoked))).hasStatus(HttpStatus.NO_CONTENT);
        expire(idOf(expired));

        MvcTestResult list = send(joao, mvc.get().uri("/v1/groups/" + flat + "/invites"));

        var body = assertThat(list).hasStatusOk().bodyJson();
        body.extractingPath("$.items[*].id")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .startsWith(idOf(last).toString())
                .contains(idOf(first).toString())
                .doesNotContain(idOf(revoked).toString(), idOf(expired).toString());
        body.extractingPath("$.items[0].createdBy.name").isEqualTo("João");
        body.extractingPath("$.items[0].expiresAt").isNotNull();
        assertThat(content(list))
                .doesNotContain(tokenOf(first))
                .doesNotContain(tokenOf(last))
                .doesNotContainIgnoringCase("token");
    }

    @Test
    void anyMemberRevokesALinkOnce() {
        UUID link = idOf(createLink(ana, flat));
        UUID otherGroupsLink = idOf(createLink(bea, ownGroup(bea)));

        assertThat(revoke(joao, flat, link)).hasStatus(HttpStatus.NO_CONTENT);
        assertProblem(revoke(joao, flat, link), HttpStatus.NOT_FOUND, "invite_not_found");
        assertProblem(revoke(ana, flat, otherGroupsLink), HttpStatus.NOT_FOUND, "invite_not_found");
        assertProblem(revoke(ana, flat, UUID.randomUUID()), HttpStatus.NOT_FOUND, "invite_not_found");
    }

    @Test
    void lookingUpShowsTheGroupWhoInvitedAndHowManyMembers() {
        String token = tokenOf(createLink(ana, flat));

        var preview = assertThat(lookUp(bea, token)).hasStatusOk().bodyJson();
        preview.extractingPath("$.groupName").isEqualTo("Flat");
        preview.extractingPath("$.currency").isEqualTo("EUR");
        preview.extractingPath("$.memberCount").isEqualTo(2);
        preview.extractingPath("$.invitedBy.userId").isEqualTo(ana.toString());
        preview.extractingPath("$.invitedBy.name").isEqualTo("Ana");
        preview.extractingPath("$.invitedBy.avatarUrl").isEqualTo("https://example.com/ana.png");
        preview.extractingPath("$.alreadyMember").isEqualTo(false);
        assertThat(lookUp(joao, token))
                .bodyJson()
                .extractingPath("$.alreadyMember")
                .isEqualTo(true);
        assertThat(content(lookUp(bea, token))).doesNotContain("@example.com");
    }

    @Test
    void acceptingAddsTheMemberWithAnAccountOnceAndPublishesMemberJoined() {
        String token = tokenOf(createLink(ana, flat));
        List<String> seen = new ArrayList<>();
        ApplicationListener<?> onJoined = ApplicationListener.forPayload((MemberJoined event) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isTrue();
            seen.add(event.userId() + " joined " + event.groupId() + ", row visible: "
                    + (count(
                                    "SELECT count(*) FROM group_members WHERE group_id = :id AND left_at IS NULL",
                                    event.groupId())
                            == 3));
        });
        context.addApplicationListener(onJoined);
        try {
            var joined = assertThat(accept(bea, token)).hasStatusOk().bodyJson();
            joined.extractingPath("$.id").isEqualTo(flat.toString());
            joined.extractingPath("$.members[*].userId")
                    .asInstanceOf(InstanceOfAssertFactories.LIST)
                    .containsExactly(ana.toString(), joao.toString(), bea.toString());

            assertThat(accept(bea, token)).hasStatusOk();
            assertThat(accept(ana, token)).hasStatusOk();
        } finally {
            context.removeApplicationListener(onJoined);
        }

        assertThat(seen).containsExactly(bea + " joined " + flat + ", row visible: true");
        assertThat(memberAccounts()).hasSize(3).allMatch(account -> account.kind() == AccountKind.GROUP_MEMBER);
        assertThat(send(bea, mvc.get().uri("/v1/groups/" + flat))).hasStatusOk();
    }

    @Test
    void someoneWhoLeftGetsTheirOldAccountBack() {
        String token = tokenOf(createLink(ana, flat));
        assertThat(accept(bea, token)).hasStatusOk();
        UUID account = accountOf(bea);
        assertThat(send(bea, mvc.post().uri("/v1/groups/" + flat + "/leave"))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(accept(bea, token)).hasStatusOk();

        assertThat(accountOf(bea)).isEqualTo(account);
        assertThat(memberAccounts()).hasSize(3);
        assertThat(send(bea, mvc.get().uri("/v1/groups/" + flat))).hasStatusOk();
    }

    @Test
    void everyTokenThatOpensNoGroupGetsTheSameAnswer() {
        String unknown = "A".repeat(43);
        String revoked = tokenOf(createLink(ana, flat));
        assertThat(revoke(ana, flat, linkWithToken(revoked))).hasStatus(HttpStatus.NO_CONTENT);
        String expired = tokenOf(createLink(ana, flat));
        expire(linkWithToken(expired));
        UUID own = ownGroup(bea);
        String archived = tokenOf(createLink(bea, own));
        assertThat(send(
                        bea,
                        mvc.patch()
                                .uri("/v1/groups/" + own)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                        {"archived": true}""")))
                .hasStatusOk();

        String answer = problem(accept(joao, unknown));
        assertThat(answer).contains("404").contains("invite_not_found");
        for (String token : List.of(unknown, revoked, expired, archived)) {
            assertThat(problem(lookUp(joao, token))).as("look up").isEqualTo(answer);
            assertThat(problem(accept(joao, token))).as("accept").isEqualTo(answer);
        }
        assertThat(count("SELECT count(*) FROM group_members WHERE group_id = :id", own))
                .isOne();
    }

    @Test
    void aTokenOfTheWrongShapeIsABadRequest() {
        assertProblem(accept(bea, "too-short"), HttpStatus.BAD_REQUEST, "bad_request");
        assertProblem(lookUp(bea, "A".repeat(44)), HttpStatus.BAD_REQUEST, "bad_request");
    }

    @Test
    void noTokenReachesTheOutput(CapturedOutput output) {
        MvcTestResult created = createLink(ana, flat);
        String token = tokenOf(created);
        String revoked = tokenOf(createLink(ana, flat));
        assertThat(revoke(ana, flat, linkWithToken(revoked))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(lookUp(bea, token)).hasStatusOk();
        assertThat(accept(bea, token)).hasStatusOk();
        assertThat(accept(bea, revoked)).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(output.getAll()).doesNotContain(token).doesNotContain(revoked);
    }

    private UUID newUser(String name, @Nullable String avatarUrl) {
        return jdbc.sql("INSERT INTO users (email, name, avatar_url) VALUES (:email, :name, :avatar) RETURNING id")
                .param("email", "invites-" + UUID.randomUUID() + "@example.com")
                .param("name", name)
                .param("avatar", avatarUrl)
                .query(UUID.class)
                .single();
    }

    private UUID ownGroup(UUID userId) {
        return idOf(send(
                userId,
                mvc.post()
                        .uri("/v1/groups")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"name": "Own"}""")));
    }

    private MvcTestResult createLink(UUID userId, UUID groupId) {
        return send(userId, mvc.post().uri("/v1/groups/" + groupId + "/invites"));
    }

    private MvcTestResult revoke(UUID userId, UUID groupId, UUID inviteId) {
        return send(userId, mvc.delete().uri("/v1/groups/" + groupId + "/invites/" + inviteId));
    }

    private MvcTestResult lookUp(UUID userId, String token) {
        return send(
                userId,
                mvc.post()
                        .uri("/v1/invites/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"token": "%s"}""".formatted(token)));
    }

    private MvcTestResult accept(UUID userId, String token) {
        return send(
                userId,
                mvc.post()
                        .uri("/v1/invites/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"token": "%s"}""".formatted(token)));
    }

    private MvcTestResult send(UUID userId, MockMvcTester.MockMvcRequestBuilder request) {
        return request.with(user(userId.toString())).with(csrf()).exchange();
    }

    private void expire(UUID inviteId) {
        jdbc.sql("UPDATE group_invites SET expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", inviteId)
                .update();
    }

    private UUID linkWithToken(String token) {
        return jdbc.sql("SELECT id FROM group_invites WHERE token_hash = sha256(convert_to(:token, 'UTF8'))")
                .param("token", token)
                .query(UUID.class)
                .single();
    }

    private List<Account> memberAccounts() {
        return ledger.accounts(
                ledger.findLedger(LedgerOwner.group(flat)).orElseThrow().id());
    }

    private UUID accountOf(UUID userId) {
        return jdbc.sql("SELECT account_id FROM group_members WHERE group_id = :group AND user_id = :user")
                .param("group", flat)
                .param("user", userId)
                .query(UUID.class)
                .single();
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    private static String tokenOf(MvcTestResult created) {
        return JsonPath.read(content(created), "$.token");
    }

    private static UUID idOf(MvcTestResult result) {
        assertThat(result.getResponse().getStatus()).isIn(200, 201);
        return UUID.fromString(JsonPath.read(content(result), "$.id"));
    }

    /** Everything a client can tell apart in a problem, but the request's own path. */
    private static String problem(MvcTestResult result) {
        Map<String, Object> body = JsonPath.read(content(result), "$");
        body.remove("instance");
        return result.getResponse().getStatus() + " " + body;
    }

    private static String content(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
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
