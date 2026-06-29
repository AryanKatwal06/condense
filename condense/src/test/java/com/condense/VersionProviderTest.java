package com.condense;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionProviderTest {

    private final VersionProvider provider = new VersionProvider();

    @Test
    void returnsAtLeastOneLine() throws Exception {
        assertThat(provider.getVersion()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void firstLineStartsWithCondense() throws Exception {
        assertThat(provider.getVersion()[0]).startsWith("condense ");
    }

    @Test
    void versionContainsDotSeparatedNumbers() throws Exception {
        String version = provider.getVersion()[0];
        // e.g. "condense 0.1.0"
        assertThat(version).matches("condense \\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    void neverReturnsNullOrEmpty() throws Exception {
        var lines = provider.getVersion();
        assertThat(lines).isNotNull().isNotEmpty();
        for (String line : lines) {
            assertThat(line).isNotNull().isNotBlank();
        }
    }
}
