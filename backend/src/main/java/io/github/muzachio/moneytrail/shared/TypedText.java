package io.github.muzachio.moneytrail.shared;

/** Text a person typed, such as a name or a description. */
public final class TypedText {

    private TypedText() {}

    /**
     * The text without the blanks at either end, empty when there is nothing else. A blank is any
     * whitespace and any Unicode space separator. {@link String#strip()} keeps the no-break spaces
     * (U+00A0, U+2007, U+202F) on purpose, and Java's validator reads the schemas' {@code \s} as ASCII
     * only, so a name of one U+00A0 would get past both.
     */
    public static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isBlank(text.charAt(start))) {
            start++;
        }
        while (end > start && isBlank(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    // Every blank is in the Basic Multilingual Plane and no surrogate is one, so chars are enough.
    private static boolean isBlank(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
