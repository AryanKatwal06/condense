package com.condense.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectFingerprintTest {

    @Test
    void outputIs12HexCharacters() {
        String fp = ProjectFingerprint.of("/home/user/myproject");
        assertThat(fp).hasSize(12).matches("[0-9a-f]{12}");
    }

    @Test
    void samePathProducesSameFingerprint() {
        String a = ProjectFingerprint.of("/tmp/test");
        String b = ProjectFingerprint.of("/tmp/test");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentPathsProduceDifferentFingerprints() {
        String a = ProjectFingerprint.of("/home/alice/project");
        String b = ProjectFingerprint.of("/home/bob/project");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void nullPathReturnsZeroFingerprint() {
        assertThat(ProjectFingerprint.of(null)).isEqualTo("000000000000");
    }

    @Test
    void blankPathReturnsZeroFingerprint() {
        assertThat(ProjectFingerprint.of("  ")).isEqualTo("000000000000");
    }

    @Test
    void currentDirDoesNotThrow() {
        assertThat(ProjectFingerprint.ofCurrentDir())
            .isNotNull()
            .hasSize(12)
            .matches("[0-9a-f]{12}");
    }
}
