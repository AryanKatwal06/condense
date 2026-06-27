package com.zapproxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionProviderTest {

    private final VersionProvider provider = new VersionProvider();

    @Test
    void returnsAtLeastOneLine() throws Exception {
        assertThat(provider.getVersion()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void firstLineStartsWithZap() throws Exception {
        assertThat(provider.getVersion()[0]).startsWith("zap ");
    }

    @Test
    void versionContainsDotSeparatedNumbers() throws Exception {
        String version = provider.getVersion()[0];
        // e.g. "zap 0.1.0"
        assertThat(version).matches("zap \\d+\\.\\d+\\.\\d+.*");
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
