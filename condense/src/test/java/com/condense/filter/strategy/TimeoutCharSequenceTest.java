package com.condense.filter.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeoutCharSequenceTest {

    @Test
    void charAtReturnsUnderlyingCharacters() {
        TimeoutCharSequence seq = new TimeoutCharSequence("abcd", 200);
        assertThat(seq.length()).isEqualTo(4);
        assertThat(seq.charAt(0)).isEqualTo('a');
        assertThat(seq.charAt(3)).isEqualTo('d');
        assertThat(seq.toString()).isEqualTo("abcd");
    }

    @Test
    void deadlineThrowsRegexTimeoutException() throws InterruptedException {
        TimeoutCharSequence seq = new TimeoutCharSequence("x", 1, "a+");
        Thread.sleep(5);
        assertThatThrownBy(() -> seq.charAt(0))
            .isInstanceOf(RegexTimeoutException.class)
            .hasMessageContaining("a+");
    }
}
