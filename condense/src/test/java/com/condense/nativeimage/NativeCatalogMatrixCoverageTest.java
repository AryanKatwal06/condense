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
}
