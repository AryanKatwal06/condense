package com.condense.nativeimage;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NativeCatalogMatrixCoverageTest {

    @Test
    void everyIndexDefinitionHasANativeMatrixRow() throws Exception {
        Set<String> indexed = NativeCatalogMatrixSupport.indexNames();
        Set<String> matrix = new LinkedHashSet<>();
        for (NativeCatalogMatrixSupport.Row row : NativeCatalogMatrixSupport.load()) {
            assertThat(row.definition()).isNotBlank();
            assertThat(row.command()).isNotBlank();
            assertThat(row.fixture()).isNotBlank();
            assertThat(row.mustContain()).isNotEmpty();
            assertThat(matrix.add(row.definition()))
                .as("duplicate matrix definition %s", row.definition())
                .isTrue();
        }
        assertThat(matrix)
            .as("native-catalog-matrix.json must list every filters/index.toml name")
            .containsExactlyInAnyOrderElementsOf(indexed);
    }

    @Test
    void passthroughWithTeeFooterDoesNotRequireStamp() throws Exception {
        String fixture;
        try (var in = NativeCatalogMatrixCoverageTest.class.getResourceAsStream("/fixtures/git-push/rejected.txt")) {
            assertThat(in).isNotNull();
            fixture = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String stdout = fixture.replace("\r\n", "\n").stripTrailing()
            + "\n[raw output saved to: /tmp/junit123/data/tee/c32f2b1a.txt]\n";
        assertThat(NativeCatalogMatrixSupport.compressedRequiresStamp(fixture, stdout)).isFalse();
        assertThat(NativeCatalogMatrixSupport.stripTeeFooter(stdout))
            .isEqualTo(fixture.replace("\r\n", "\n").stripTrailing());
    }

    @Test
    void compressedBodyRequiresStampEvenWithTeeFooter() {
        String fixture = "lots of noise\nfailed\nmore noise\n";
        String stamped = "condense[filtered]\nfailed\n[raw output saved to: /tmp/tee.txt]\n";
        String unstamped = "failed\n[raw output saved to: /tmp/tee.txt]\n";
        assertThat(NativeCatalogMatrixSupport.compressedRequiresStamp(fixture, stamped)).isTrue();
        assertThat(NativeCatalogMatrixSupport.compressedRequiresStamp(fixture, unstamped)).isTrue();
    }
}
