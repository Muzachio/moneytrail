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
import io.github.muzachio.moneytrail.shared.api.model.CategoryIcon;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
        body.extractingPath("$.items[*].default")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsOnly(true);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM category_keys k
                        JOIN accounts a ON a.id = k.category_id
                        JOIN ledgers l ON l.id = a.ledger_id
                        WHERE l.owner_id = :userId AND k.key = a.subtype""").param("userId", ana).query(Long.class).single())
                .as("each default starts called by its own known name")
                .isEqualTo(12);
        // The design canvas' icon of each key.
        body.extractingPath("$.items[*].icon")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly(
                        "house",
                        "utensils",
                        "tram-front",
                        "receipt",
                        "shopping-bag",
                        "ticket",
                        "heart",
                        "tag",
                        "banknote",
                        "rotate-ccw",
                        "gift",
                        "tag");
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
        body.extractingPath("$.default").isEqualTo(false);
        body.extractingPath("$.archived").isEqualTo(false);
        // A name is shown as typed, even one the app knows: the app sends `key` for those.
        assertThat(body(created)).doesNotContain("\"key\"");
        // After the default expenses, before the income ones.
        assertThat(get(ana)).bodyJson().extractingPath("$.items[8].name").isEqualTo("Pets");
    }

    @Test
    void stripsNoBreakSpacesAroundANameAndKeepsTheOnesInside() {
        assertThat(post(ana, """
                        {"kind": "EXPENSE", "name": "\\u00a0Pet\\u00a0food\\u202f"}"""))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$.name")
                .isEqualTo("Pet\u00a0food");
    }

    @Test
    void createsACategoryCalledByAKnownNameKeptInEnglish() {
        MvcTestResult created = post(ana, """
                {"kind": "EXPENSE", "key": "gym", "icon": "dumbbell"}""");

        var body = assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson();
        body.extractingPath("$.key").isEqualTo("gym");
        body.extractingPath("$.default").isEqualTo(false);
        body.extractingPath("$.icon").isEqualTo("dumbbell");
        assertThat(body(created)).doesNotContain("\"name\"");
        UUID gym = idOf(created);
        assertThat(ledger.findAccount(gym))
                .hasValueSatisfying(account -> assertThat(account.name()).isEqualTo("Gym"));
        assertThat(keyOf(gym)).isEqualTo("gym");
        assertThat(get(ana)).bodyJson().extractingPath("$.items[8].key").isEqualTo("gym");
    }

    /** The app sends `key` for "Alimentação" typed in Portuguese, and so finds the default food. */
    @Test
    void aKnownNameIsTakenInEveryLanguage() {
        assertNameTaken(post(ana, """
                {"kind": "EXPENSE", "key": "food"}"""));
        assertThat(post(ana, """
                        {"kind": "EXPENSE", "key": "gym"}""")).hasStatus(HttpStatus.CREATED);
        assertNameTaken(post(ana, """
                {"kind": "EXPENSE", "key": "gym"}"""));
        assertNameTaken(post(ana, """
                {"kind": "EXPENSE", "name": "GYM"}"""));

        assertThat(post(ana, """
                        {"kind": "EXPENSE", "key": "gifts-given"}"""))
                .as("Gifts, but an expense: the income default of that name is another kind")
                .hasStatus(HttpStatus.CREATED);
        assertThat(post(signedInUser(), """
                        {"kind": "EXPENSE", "key": "gym"}""")).as("another user").hasStatus(HttpStatus.CREATED);
    }

    @Test
    void aDefaultsKnownNameIsFreeWhileTheDefaultIsArchived() {
        UUID food = defaultCategory(ana, "food");
        assertThat(patch(ana, food, """
                        {"archived": true}""")).hasStatusOk();

        var created = assertThat(post(ana, """
                        {"kind": "EXPENSE", "key": "food"}""")).hasStatus(HttpStatus.CREATED).bodyJson();
        created.extractingPath("$.key").isEqualTo("food");
        created.extractingPath("$.default").isEqualTo(false);

        assertNameTaken(patch(ana, food, """
                {"archived": false}"""));
    }

    @Test
    void renamesACategoryWithAKnownNameOrATypedOne() {
        UUID pilates = idOf(post(ana, """
                {"kind": "EXPENSE", "name": "Pilates"}"""));

        MvcTestResult known = patch(ana, pilates, """
                {"key": "gym"}""");
        assertThat(known).hasStatusOk().bodyJson().extractingPath("$.key").isEqualTo("gym");
        assertThat(body(known)).doesNotContain("\"name\"");
        assertThat(ledger.findAccount(pilates))
                .hasValueSatisfying(account -> assertThat(account.name()).isEqualTo("Gym"));

        MvcTestResult typed = patch(ana, pilates, """
                {"name": "Pilates"}""");
        assertThat(typed).hasStatusOk().bodyJson().extractingPath("$.name").isEqualTo("Pilates");
        assertThat(body(typed)).doesNotContain("\"key\"");
        assertThat(keyOf(pilates)).isNull();
    }

    @Test
    void aDefaultCategoryCalledByAnotherKnownNameStaysADefault() {
        UUID shopping = defaultCategory(ana, "shopping");

        var renamed = assertThat(patch(ana, shopping, """
                        {"key": "groceries"}""")).hasStatusOk().bodyJson();
        renamed.extractingPath("$.key").isEqualTo("groceries");
        renamed.extractingPath("$.default").isEqualTo(true);
        renamed.extractingPath("$.icon").isEqualTo("shopping-bag");

        var listed = assertThat(get(ana)).bodyJson();
        listed.extractingPath("$.items[4].id")
                .as("still in the defaults' order")
                .isEqualTo(shopping.toString());
        listed.extractingPath("$.items[4].key").isEqualTo("groceries");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"kind\": \"INCOME\", \"key\": \"gym\"}",
                "{\"kind\": \"EXPENSE\", \"key\": \"salary\"}",
                "{\"kind\": \"EXPENSE\", \"key\": \"dragon\"}",
                "{\"kind\": \"EXPENSE\", \"key\": \"gym\", \"name\": \"Gym\"}",
                "{\"kind\": \"EXPENSE\", \"key\": null, \"name\": null}"
            })
    void refusesAKeyOfTheOtherKindAKeyNotOnTheListOrNeitherOrBoth(String body) {
        assertBadRequest(post(ana, body));
        assertThat(get(ana))
                .bodyJson()
                .extractingPath("$.items")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .hasSize(12);
    }

    @Test
    void createsACategoryWithTheIconPickedForIt() {
        MvcTestResult created = post(ana, """
                {"kind": "EXPENSE", "name": "Pets", "icon": "paw-print"}""");

        assertThat(created)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$.icon")
                .isEqualTo("paw-print");
        var listed = assertThat(get(ana)).bodyJson();
        listed.extractingPath("$.items[8].name").isEqualTo("Pets");
        listed.extractingPath("$.items[8].icon").isEqualTo("paw-print");
        assertThat(jdbc.sql("SELECT icon FROM category_icons WHERE category_id = :id")
                        .param("id", idOf(created))
                        .query(String.class)
                        .single())
                .isEqualTo("paw-print");
    }

    @Test
    void aCategoryCreatedWithoutAnIconHasTheTag() {
        assertThat(post(ana, """
                        {"kind": "INCOME", "name": "Side jobs"}"""))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$.icon")
                .isEqualTo("tag");
    }

    /** A category of the user's own from before V11 has no row in category_icons. */
    @Test
    void aCategoryWithoutAPickedIconHasTheTag() {
        UUID pets = idOf(post(ana, """
                {"kind": "EXPENSE", "name": "Pets", "icon": "paw-print"}"""));
        jdbc.sql("DELETE FROM category_icons WHERE category_id = :id")
                .param("id", pets)
                .update();

        assertThat(get(ana)).bodyJson().extractingPath("$.items[8].icon").isEqualTo("tag");
        assertThat(patch(ana, pets, """
                        {"icon": "dumbbell"}"""))
                .as("and it can pick one")
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.icon")
                .isEqualTo("dumbbell");
    }

    @Test
    void changesTheIconOfACategoryOfTheUsersOwnAndLeavesTheRestAsItWas() {
        UUID pets = idOf(post(ana, """
                {"kind": "EXPENSE", "name": "Pets", "icon": "paw-print"}"""));

        var changed = assertThat(patch(ana, pets, """
                        {"icon": "heart"}""")).hasStatusOk().bodyJson();
        changed.extractingPath("$.icon").isEqualTo("heart");
        changed.extractingPath("$.name").isEqualTo("Pets");
        changed.extractingPath("$.archived").isEqualTo(false);
        assertThat(get(ana)).bodyJson().extractingPath("$.items[8].icon").isEqualTo("heart");

        var renamed = assertThat(patch(ana, pets, """
                        {"name": "Cats", "icon": "paw-print", "archived": true}""")).hasStatusOk().bodyJson();
        renamed.extractingPath("$.name").isEqualTo("Cats");
        renamed.extractingPath("$.icon").isEqualTo("paw-print");
        renamed.extractingPath("$.archived").isEqualTo(true);

        assertThat(patch(ana, pets, """
                        {"archived": false}"""))
                .as("restoring keeps the icon")
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.icon")
                .isEqualTo("paw-print");
    }

    @Test
    void aRefusedNameLeavesTheIconAsItWas() {
        UUID pets = idOf(post(ana, """
                {"kind": "EXPENSE", "name": "Pets", "icon": "paw-print"}"""));

        assertNameTaken(patch(ana, pets, """
                {"name": "Food", "icon": "utensils"}"""));

        assertThat(get(ana)).bodyJson().extractingPath("$.items[8].icon").isEqualTo("paw-print");
    }

    @Test
    void aDefaultCategoryKeepsItsIcon() {
        UUID food = defaultCategory(ana, "food");

        assertBadRequest(patch(ana, food, """
                {"icon": "car"}"""));
        assertBadRequest(patch(ana, food, """
                {"name": "Groceries", "icon": "utensils"}"""));

        var listed = assertThat(get(ana)).bodyJson();
        listed.extractingPath("$.items[?(@.key == 'food')].icon")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("utensils");
        listed.extractingPath("$.items[?(@.key == 'food')].name")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .as("nothing else changed either")
                .isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM category_icons WHERE category_id = :id")
                        .param("id", food)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** The database's check has every name of the API's list (IconTests: so has Icon). */
    @ParameterizedTest
    @EnumSource(CategoryIcon.class)
    void everyIconOnTheListCanBePickedAndStored(CategoryIcon icon) {
        UUID category = idOf(post(ana, """
                {"kind": "EXPENSE", "name": "Pets", "icon": "%s"}""".formatted(icon.getValue())));
        assertThat(patch(ana, category, """
                        {"icon": "tag"}""")).hasStatusOk();

        assertThat(patch(ana, category, """
                        {"icon": "%s"}""".formatted(icon.getValue()))).hasStatusOk();
        assertThat(get(ana)).bodyJson().extractingPath("$.items[8].icon").isEqualTo(icon.getValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dragon", "Utensils", "PAW_PRINT", "7"})
    void refusesAnIconNotOnTheList(String icon) {
        UUID pets = idOf(post(ana, """
                {"kind": "EXPENSE", "name": "Pets"}"""));

        assertBadRequest(post(ana, """
                {"kind": "EXPENSE", "name": "Cats", "icon": "%s"}""".formatted(icon)));
        assertBadRequest(patch(ana, pets, """
                {"icon": "%s"}""".formatted(icon)));
        assertThat(get(ana)).bodyJson().extractingPath("$.items[8].icon").isEqualTo("tag");
        assertThat(get(ana))
                .bodyJson()
                .extractingPath("$.items[*].name")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactly("Pets");
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
    void aDefaultCategoryRenamedWithATypedNameShowsItAndStaysADefault() {
        UUID food = defaultCategory(ana, "food");

        MvcTestResult renamed = patch(ana, food, """
                {"name": "Comida"}""");
        var body = assertThat(renamed).hasStatusOk().bodyJson();
        body.extractingPath("$.name").isEqualTo("Comida");
        body.extractingPath("$.default").isEqualTo(true);
        body.extractingPath("$.icon").isEqualTo("utensils");
        body.extractingPath("$.archived").isEqualTo(false);
        assertThat(body(renamed)).as("a name or a key, never both").doesNotContain("\"key\"");

        assertNameTaken(patch(ana, food, """
                {"name": "transport"}"""));
        var back = assertThat(patch(ana, food, """
                        {"key": "food"}"""))
                .as("its own key back, so translated again")
                .hasStatusOk()
                .bodyJson();
        back.extractingPath("$.key").isEqualTo("food");
        back.doesNotHavePath("$.name");
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
        var restored = assertThat(patch(ana, gifts, """
                        {"key": "gifts", "archived": false}""")).hasStatusOk().bodyJson();
        restored.doesNotHavePath("$.name");
        restored.extractingPath("$.key").isEqualTo("gifts");
    }

    @Test
    void anotherUsersCategoryIsNotFound() {
        UUID anasFood = defaultCategory(ana, "food");
        UUID anasPets = idOf(post(ana, """
                {"kind": "EXPENSE", "name": "Pets", "icon": "paw-print"}"""));
        UUID rui = signedInUser();

        assertNotFound(patch(rui, anasFood, """
                {"name": "Mine now"}"""));
        assertNotFound(patch(rui, anasFood, """
                {"archived": true}"""));
        assertNotFound(patch(rui, anasPets, """
                {"icon": "car"}"""));
        assertNotFound(patch(rui, anasPets, """
                {"key": "gym"}"""));

        assertThat(ledger.findAccount(anasFood)).hasValueSatisfying(food -> {
            assertThat(food.name()).isEqualTo("Food");
            assertThat(food.archived()).isFalse();
        });
        assertThat(get(ana)).bodyJson().extractingPath("$.items[8].icon").isEqualTo("paw-print");
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
                "{\"kind\": \"EXPENSE\", \"name\": \"\\u00a0\"}",
                "{\"kind\": \"EXPENSE\", \"name\": \"\\u202f\"}",
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
    @ValueSource(
            strings = {
                "{}",
                "{\"name\": \" \"}",
                "{\"name\": \"\\u00a0\"}",
                "{\"name\": \"\\u202f\"}",
                "{\"archived\": null}",
                "{\"icon\": null}",
                "{\"key\": null}",
                "{\"key\": \"salary\"}",
                "{\"key\": \"dragon\"}",
                "{\"key\": \"groceries\", \"name\": \"Groceries\"}"
            })
    void refusesAnInvalidUpdate(String body) {
        UUID food = defaultCategory(ana, "food");

        assertBadRequest(patch(ana, food, body));
        assertThat(keyOf(food)).as("still called by its own name").isEqualTo("food");
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

    private static UUID idOf(MvcTestResult created) {
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(JsonPath.read(body(created), "$.id"));
    }

    /** The key of the known name a category is called by, or null when it is called by a typed name. */
    private @Nullable String keyOf(UUID categoryId) {
        return jdbc.sql("SELECT key FROM category_keys WHERE category_id = :id")
                .param("id", categoryId)
                .query(String.class)
                .optional()
                .orElse(null);
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
