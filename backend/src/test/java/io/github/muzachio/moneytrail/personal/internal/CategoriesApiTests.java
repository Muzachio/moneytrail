package io.github.muzachio.moneytrail.personal.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.jayway.jsonpath.JsonPath;
import io.github.muzachio.moneytrail.PostgresIntegrationTest;
import io.github.muzachio.moneytrail.identity.UserSignedIn;
import io.github.muzachio.moneytrail.ledger.Account;
import io.github.muzachio.moneytrail.ledger.AccountKind;
import io.github.muzachio.moneytrail.ledger.LedgerOwner;
import io.github.muzachio.moneytrail.ledger.LedgerService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/** GET, POST and PATCH /v1/categories as in api/openapi.yaml. */
@AutoConfigureMockMvc
class CategoriesApiTests extends PostgresIntegrationTest {

    private static final String CATEGORIES = "/v1/categories";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    private UUID ana;

    @BeforeEach
    void signInAna() {
        ana = signedInUser();
    }

    @Test
    void listsTheDefaultCategoriesByKeyWithoutNamesExpensesFirst() {
        var body = assertThat(get(ana))
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson();

        body.extractingPath("$.items[*].key")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(
                        "housing",
                        "food",
                        "transport",
                        "bills",
                        "shopping",
                        "leisure",
                        "health",
                        "other",
                        "salary",
                        "refunds",
                        "gifts",
                        "other-income");
        body.extractingPath("$.items[*].kind")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(
                        "EXPENSE", "EXPENSE", "EXPENSE", "EXPENSE", "EXPENSE", "EXPENSE", "EXPENSE", "EXPENSE",
                        "INCOME", "INCOME", "INCOME", "INCOME");
        body.extractingPath("$.items[*].archived")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsOnly(false);
        // Not even null: the app shows its translation of the key when there is no name.
        body.extractingPath("$.items[*].name")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .isEmpty();
    }

    @Test
    void aSessionFromBeforeLedgersStillGetsTheDefaultCategories() {
        UUID oldUser = newUser();

        assertThat(get(oldUser))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.items")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .hasSize(12);
        assertThat(ledger.findLedger(LedgerOwner.user(oldUser))).isPresent();
    }

    @Test
    void createsACategoryWithoutTheSpacesAroundItsName() {
        MvcTestResult created = post(ana, """
                {"kind": "EXPENSE", "name": "  Pets  "}""");

        var body = assertThat(created)
                .hasStatus(HttpStatus.CREATED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson();
        body.extractingPath("$.kind").isEqualTo("EXPENSE");
        body.extractingPath("$.name").isEqualTo("Pets");
        body.extractingPath("$.archived").isEqualTo(false);
        assertThat(body(created)).doesNotContain("\"key\"");
        // After the default expenses, before the income ones.
        assertThat(get(ana)).bodyJson().extractingPath("$.items[8].name").isEqualTo("Pets");
    }

    @Test
    void aNameIsTakenByAnActiveCategoryOfTheSameKindWhateverItsCase() {
        assertNameTaken(post(ana, """
                {"kind": "EXPENSE", "name": "FOOD"}"""));
        assertThat(post(ana, """
                        {"kind": "EXPENSE", "name": "Saúde"}""")).hasStatus(HttpStatus.CREATED);
        assertNameTaken(post(ana, """
                {"kind": "EXPENSE", "name": "SAÚDE"}"""));

        assertThat(post(ana, """
                        {"kind": "INCOME", "name": "Food"}""")).as("another kind").hasStatus(HttpStatus.CREATED);
        assertThat(post(signedInUser(), """
                        {"kind": "EXPENSE", "name": "Saúde"}""")).as("another user").hasStatus(HttpStatus.CREATED);
    }

    @Test
    void anArchivedCategorysNameCanBeUsedAgainButThenItCannotBeRestored() {
        UUID food = defaultCategory(ana, "food");
        assertThat(patch(ana, food, """
                        {"archived": true}"""))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.archived")
                .isEqualTo(true);

        assertThat(post(ana, """
                        {"kind": "EXPENSE", "name": "food"}""")).hasStatus(HttpStatus.CREATED);

        assertNameTaken(patch(ana, food, """
                {"archived": false}"""));
        assertThat(patch(ana, food, """
                        {"name": "Old food", "archived": false}"""))
                .as("restored under a free name")
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.archived")
                .isEqualTo(false);
    }

    @Test
    void renamingADefaultCategoryKeepsItsKeyAndShowsTheNewName() {
        UUID food = defaultCategory(ana, "food");

        var renamed = assertThat(patch(ana, food, """
                        {"name": "Groceries"}""")).hasStatusOk().bodyJson();
        renamed.extractingPath("$.key").isEqualTo("food");
        renamed.extractingPath("$.name").isEqualTo("Groceries");
        renamed.extractingPath("$.archived").isEqualTo(false);

        assertNameTaken(patch(ana, food, """
                {"name": "transport"}"""));
        assertThat(patch(ana, food, """
                        {"name": "Food"}"""))
                .as("back to the default name, so back to the translation")
                .hasStatusOk()
                .bodyJson()
                .doesNotHavePath("$.name");
    }

    @Test
    void archivesAndRestoresACategory() {
        UUID salary = defaultCategory(ana, "salary");

        assertThat(patch(ana, salary, """
                        {"archived": true}"""))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.archived")
                .isEqualTo(true);
        assertThat(get(ana))
                .bodyJson()
                .extractingPath("$.items[?(@.key == 'salary')].archived")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(true);

        assertThat(patch(ana, salary, """
                        {"archived": false}"""))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.archived")
                .isEqualTo(false);
    }

    @Test
    void archivingAndRenamingAtOnceChecksTheNameOnlyAmongTheOthersThatStayActive() {
        UUID gifts = defaultCategory(ana, "gifts");

        var archived = assertThat(patch(ana, gifts, """
                        {"name": "Salary", "archived": true}""")).hasStatusOk().bodyJson();
        archived.extractingPath("$.name").isEqualTo("Salary");
        archived.extractingPath("$.archived").isEqualTo(true);

        assertNameTaken(patch(ana, gifts, """
                {"archived": false}"""));
        assertNameTaken(patch(ana, gifts, """
                {"name": "salary", "archived": false}"""));
        assertThat(patch(ana, gifts, """
                        {"name": "Gifts", "archived": false}""")).hasStatusOk().bodyJson().doesNotHavePath("$.name");
    }

    @Test
    void anotherUsersCategoryIsNotFound() {
        UUID anasFood = defaultCategory(ana, "food");
        UUID rui = signedInUser();

        assertNotFound(patch(rui, anasFood, """
                {"name": "Mine now"}"""));
        assertNotFound(patch(rui, anasFood, """
                {"archived": true}"""));

        assertThat(ledger.findAccount(anasFood)).hasValueSatisfying(food -> {
            assertThat(food.name()).isEqualTo("Food");
            assertThat(food.archived()).isFalse();
        });
    }

    @Test
    void accountsThatAreNotCategoriesAreNotFound() {
        UUID openingBalances = ledger
                .accounts(ledger.findLedger(LedgerOwner.user(ana)).orElseThrow().id())
                .stream()
                .filter(account -> account.kind() == AccountKind.EQUITY)
                .map(Account::id)
                .findFirst()
                .orElseThrow();

        assertNotFound(patch(ana, openingBalances, """
                {"name": "Mine"}"""));
        assertNotFound(patch(ana, UUID.randomUUID(), """
                {"name": "Mine"}"""));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"kind\": \"EXPENSE\", \"name\": \"\"}",
                "{\"kind\": \"EXPENSE\", \"name\": \"   \"}",
                "{\"kind\": \"EXPENSE\", \"name\": \" \"}",
                "{\"kind\": \"EXPENSE\", \"name\": \"123456789012345678901234567890123456789012345678901\"}",
                "{\"kind\": \"EXPENSE\"}",
                "{\"name\": \"Pets\"}",
                "{\"kind\": \"ASSET\", \"name\": \"Pets\"}",
                "not json"
            })
    void refusesAnInvalidNewCategory(String body) {
        assertBadRequest(post(ana, body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\": \" \"}", "{\"archived\": null}"})
    void refusesAnInvalidUpdate(String body) {
        assertBadRequest(patch(ana, defaultCategory(ana, "food"), body));
    }

    @Test
    void withoutASessionEveryEndpointAnswers401() {
        assertThat(mvc.get().uri(CATEGORIES)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post()
                        .uri(CATEGORIES)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\": \"EXPENSE\", \"name\": \"Pets\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.patch()
                        .uri(CATEGORIES + "/" + UUID.randomUUID())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"archived\": true}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    /** A user who has signed in: the sign-in transaction published UserSignedIn. */
    private UUID signedInUser() {
        UUID userId = newUser();
        transactions.executeWithoutResult(status -> events.publishEvent(new UserSignedIn(userId)));
        return userId;
    }

    private UUID newUser() {
        return jdbc.sql("INSERT INTO users (email, name) VALUES (:email, 'Categories Test') RETURNING id")
                .param("email", "categories-" + UUID.randomUUID() + "@example.com")
                .query(UUID.class)
                .single();
    }

    private UUID defaultCategory(UUID userId, String key) {
        MvcTestResult list = get(userId);
        assertThat(list).hasStatusOk();
        List<String> ids = JsonPath.read(body(list), "$.items[?(@.key == '" + key + "')].id");
        assertThat(ids).hasSize(1);
        return UUID.fromString(ids.getFirst());
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private MvcTestResult get(UUID userId) {
        return mvc.get().uri(CATEGORIES).with(user(userId.toString())).exchange();
    }

    private MvcTestResult post(UUID userId, String body) {
        return mvc.post()
                .uri(CATEGORIES)
                .with(user(userId.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult patch(UUID userId, UUID categoryId, String body) {
        return mvc.patch()
                .uri(CATEGORIES + "/" + categoryId)
                .with(user(userId.toString()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static void assertNameTaken(MvcTestResult result) {
        assertProblem(result, HttpStatus.CONFLICT, "category_name_taken");
    }

    private static void assertNotFound(MvcTestResult result) {
        assertProblem(result, HttpStatus.NOT_FOUND, "category_not_found");
    }

    private static void assertBadRequest(MvcTestResult result) {
        assertProblem(result, HttpStatus.BAD_REQUEST, "bad_request");
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
