package com.condense.filter.strategy;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedRegexTest {

    @Test
    void findMatchesOrdinaryInput() {
        Pattern word = Pattern.compile("hello");
        assertThat(BoundedRegex.find(word, "say hello there")).isTrue();
        assertThat(BoundedRegex.find(word, "nope")).isFalse();
    }

    @Test
    void replaceAllStripsASimpleSuffix() {
        Pattern suffix = Pattern.compile("\\s+\\(x\\d+\\)$");
        assertThat(BoundedRegex.replaceAll(suffix, "line (x3)", "")).isEqualTo("line");
    }

    @Test
    void timeoutMillisMatchesOverrideBudget() {
        assertThat(BoundedRegex.TIMEOUT_MS).isEqualTo(200L);
    }

    @Test
    void matcherUsesTimeoutCharSequence() {
        Pattern word = Pattern.compile("hello");
        var matcher = BoundedRegex.matcher(word, "hello");
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).isEqualTo("hello");
    }
}
