package com.condense.core;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TeeModeTest {

    @ParameterizedTest
    @CsvSource({
        "failures, FAILURES",
        "FAILURES, FAILURES",
        "Failures, FAILURES",
        "always,   ALWAYS",
        "ALWAYS,   ALWAYS",
        "omissions, OMISSIONS",
        "OMISSIONS, OMISSIONS",
        "Omissions, OMISSIONS",
        "never,    NEVER",
        "NEVER,    NEVER",
        "garbage,  FAILURES",
        ",         FAILURES"
    })
    void fromStringIsCaseInsensitiveWithSaneDefault(String input, TeeMode expected) {
        assertThat(TeeMode.fromString(input)).isEqualTo(expected);
    }

    @Test
    void toValueIsLowerCase() {
        assertThat(TeeMode.FAILURES.toValue()).isEqualTo("failures");
        assertThat(TeeMode.ALWAYS.toValue()).isEqualTo("always");
        assertThat(TeeMode.OMISSIONS.toValue()).isEqualTo("omissions");
        assertThat(TeeMode.NEVER.toValue()).isEqualTo("never");
    }
}
