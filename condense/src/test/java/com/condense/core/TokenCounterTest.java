package com.condense.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCounterTest {

    @Test
    void nullReturnsZero() {
        assertThat(TokenCounter.count((String) null)).isZero();
    }

    @Test
    void emptyStringReturnsZero() {
        assertThat(TokenCounter.count("")).isZero();
    }

    @Test
    void asciiLatinUsesCeilingDivisor() {
        assertThat(TokenCounter.count("a")).isEqualTo(1);
        assertThat(TokenCounter.count("abcd")).isEqualTo(1);
        assertThat(TokenCounter.count("abcde")).isEqualTo(2);
        assertThat(TokenCounter.count("abcdefgh")).isEqualTo(2);
        assertThat(TokenCounter.count("abcdefghi")).isEqualTo(3);
    }

    @Test
    void typicalGitStatusOutputIsASmallEstimate() {
        String output = "On branch main\n" +
            "Your branch is up to date with 'origin/main'.\n\n" +
            "Changes not staged for commit:\n" +
            "  modified:   src/main/java/com/example/Foo.java\n" +
            "  modified:   src/main/java/com/example/Bar.java\n";
        assertThat(TokenCounter.count(output)).isBetween(20, 200);
    }

    @Test
    void savingsPct_ninetyPercentCompression() {
        String raw = "a".repeat(400);
        String filtered = "a".repeat(40);
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
