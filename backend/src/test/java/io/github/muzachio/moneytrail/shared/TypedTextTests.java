package io.github.muzachio.moneytrail.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TypedTextTests {

    @ParameterizedTest
    @ValueSource(chars = {' ', '\t', '\n', '\u00a0', '\u2007', '\u202f', '\u2003', '\u3000'})
    void stripsWhitespaceAndUnicodeSpacesAtBothEnds(char blank) {
        assertThat(TypedText.strip(blank + "Pets" + blank + blank)).isEqualTo("Pets");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\u00a0", "\u2007", "\u202f", "\t\u00a0\u3000\n"})
    void leavesNothingOfTextThatIsOnlyBlanks(String blanks) {
        assertThat(TypedText.strip(blanks)).isEmpty();
    }

    @Test
    void keepsTheSpacesInside() {
        assertThat(TypedText.strip("\u00a0Summer\u00a0trip\u202f2026 ")).isEqualTo("Summer\u00a0trip\u202f2026");
    }

    @Test
    void keepsACharacterOutsideTheBasicPlaneAtEitherEnd() {
        String pawPrints = "\ud83d\udc3e";

        assertThat(TypedText.strip("\u00a0" + pawPrints + "\u00a0")).isEqualTo(pawPrints);
    }
}
