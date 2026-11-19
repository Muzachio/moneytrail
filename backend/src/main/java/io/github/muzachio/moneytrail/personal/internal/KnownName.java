package io.github.muzachio.moneytrail.personal.internal;

import static io.github.muzachio.moneytrail.ledger.AccountKind.EXPENSE;
import static io.github.muzachio.moneytrail.ledger.AccountKind.INCOME;

import io.github.muzachio.moneytrail.ledger.AccountKind;
import java.util.Arrays;
import java.util.Optional;

/**
 * The category names the app knows (api/openapi.yaml, {@code CategoryKey}), which it shows in each
 * person's language. The first twelve are the default categories' names.
 *
 * <p>A category with one of these names keeps the key in {@code category_keys} and the English name
 * in the account's name, so the ledger's rule against repeated names (V8) compares known names in
 * one language: "Alimentação" and "Food" are the same name. The app's English translation of each
 * key is this English name; its Portuguese one, and V12's, the other half of the list.
 */
enum KnownName {
    HOUSING(EXPENSE, "housing", "Housing"),
    FOOD(EXPENSE, "food", "Food"),
    TRANSPORT(EXPENSE, "transport", "Transport"),
    BILLS(EXPENSE, "bills", "Bills"),
    SHOPPING(EXPENSE, "shopping", "Shopping"),
    LEISURE(EXPENSE, "leisure", "Leisure"),
    HEALTH(EXPENSE, "health", "Health"),
    OTHER(EXPENSE, "other", "Other"),
    SALARY(INCOME, "salary", "Salary"),
    REFUNDS(INCOME, "refunds", "Refunds"),
    GIFTS(INCOME, "gifts", "Gifts"),
    OTHER_INCOME(INCOME, "other-income", "Other income"),
    GROCERIES(EXPENSE, "groceries", "Groceries"),
    RESTAURANTS(EXPENSE, "restaurants", "Restaurants"),
    RENT(EXPENSE, "rent", "Rent"),
    CONDO_FEES(EXPENSE, "condo-fees", "Condo fees"),
    ELECTRICITY(EXPENSE, "electricity", "Electricity"),
    WATER(EXPENSE, "water", "Water"),
    GAS(EXPENSE, "gas", "Gas"),
    PHONE_INTERNET(EXPENSE, "phone-internet", "Phone and internet"),
    SUBSCRIPTIONS(EXPENSE, "subscriptions", "Subscriptions"),
    FUEL(EXPENSE, "fuel", "Fuel"),
    TOLLS(EXPENSE, "tolls", "Tolls"),
    INSURANCE(EXPENSE, "insurance", "Insurance"),
    PHARMACY(EXPENSE, "pharmacy", "Pharmacy"),
    PERSONAL_CARE(EXPENSE, "personal-care", "Personal care"),
    CLOTHES(EXPENSE, "clothes", "Clothes"),
    GYM(EXPENSE, "gym", "Gym"),
    EDUCATION(EXPENSE, "education", "Education"),
    KIDS(EXPENSE, "kids", "Kids"),
    PETS(EXPENSE, "pets", "Pets"),
    TRAVEL(EXPENSE, "travel", "Travel"),
    GIFTS_GIVEN(EXPENSE, "gifts-given", "Gifts"),
    TAXES(EXPENSE, "taxes", "Taxes"),
    FREELANCE(INCOME, "freelance", "Freelance"),
    BONUS(INCOME, "bonus", "Bonus"),
    INTEREST(INCOME, "interest", "Interest"),
    DIVIDENDS(INCOME, "dividends", "Dividends"),
    HOLIDAY_ALLOWANCE(INCOME, "holiday-allowance", "Holiday allowance"),
    CHRISTMAS_ALLOWANCE(INCOME, "christmas-allowance", "Christmas allowance"),
    SALES(INCOME, "sales", "Sales"),
    PENSION(INCOME, "pension", "Pension");

    private final AccountKind kind;
    private final String key;
    private final String english;

    KnownName(AccountKind kind, String key, String english) {
        this.kind = kind;
        this.key = key;
        this.english = english;
    }

    AccountKind kind() {
        return kind;
    }

    /** The name of the key in the API and in {@code category_keys}. */
    String key() {
        return key;
    }

    /** The name kept as the account's name, which the rule against repeated names compares. */
    String english() {
        return english;
    }

    static Optional<KnownName> of(String key) {
        return Arrays.stream(values()).filter(name -> name.key.equals(key)).findFirst();
    }
}
