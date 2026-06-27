package com.zapproxy.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCounterTest {

    @Test
    void nullReturnsZero() {
        assertThat(TokenCounter.count(null)).isZero();
    }

    @Test
    void emptyStringReturnsZero() {
        assertThat(TokenCounter.count("")).isZero();
    }

    @ParameterizedTest(name = "count(''{0}'') == {1}")
    @CsvSource({
        "a,       1",
        "abcd,    1",
        "abcde,   2",
        "abcdefgh, 2",
        "abcdefghi, 3"
    })
    void ceilingDivisionByFour(String input, int expected) {
        assertThat(TokenCounter.count(input)).isEqualTo(expected);
    }

    @Test
    void typicalGitStatusOutputIsBetween40And200Tokens() {
        String output = "On branch main\n" +
            "Your branch is up to date with 'origin/main'.\n\n" +
            "Changes not staged for commit:\n" +
            "  modified:   src/main/java/com/example/Foo.java\n" +
            "  modified:   src/main/java/com/example/Bar.java\n";
        assertThat(TokenCounter.count(output)).isBetween(40, 200);
    }

    @Test
    void savingsPct_ninetyPercentCompression() {
        String raw      = "a".repeat(400); // 100 tokens
        String filtered = "a".repeat(40);  // 10 tokens
        assertThat(TokenCounter.savingsPct(raw, filtered)).isEqualTo(90);
    }

    @Test
    void savingsPct_zeroWhenRawIsEmpty() {
        assertThat(TokenCounter.savingsPct("", "anything")).isZero();
    }

    @Test
    void savingsPct_zeroWhenNoCompression() {
        String text = "same text";
        assertThat(TokenCounter.savingsPct(text, text)).isZero();
    }
}
