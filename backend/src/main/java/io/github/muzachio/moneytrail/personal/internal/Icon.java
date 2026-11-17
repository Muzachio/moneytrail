package io.github.muzachio.moneytrail.personal.internal;

import java.util.Arrays;

/**
 * The icons a category can have (api/openapi.yaml, {@code CategoryIcon}), each by the name the API
 * and {@code category_icons} use. The default categories have the first eleven; a category of the
 * user's own can have any of them, and has {@link #TAG} until one is picked.
 */
enum Icon {
    HOUSE("house"),
    UTENSILS("utensils"),
    TRAM_FRONT("tram-front"),
    RECEIPT("receipt"),
    SHOPPING_BAG("shopping-bag"),
    TICKET("ticket"),
    HEART("heart"),
    TAG("tag"),
    BANKNOTE("banknote"),
    ROTATE_CCW("rotate-ccw"),
    GIFT("gift"),
    CAR("car"),
    FUEL("fuel"),
    PLANE("plane"),
    COFFEE("coffee"),
    SHIRT("shirt"),
    SMARTPHONE("smartphone"),
    GRADUATION_CAP("graduation-cap"),
    BABY("baby"),
    PAW_PRINT("paw-print"),
    DUMBBELL("dumbbell"),
    WRENCH("wrench"),
    PIGGY_BANK("piggy-bank"),
    BRIEFCASE("briefcase");

    private final String value;

    Icon(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }

    static Icon of(String value) {
        return Arrays.stream(values())
                .filter(icon -> icon.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No icon is called " + value));
    }
}
