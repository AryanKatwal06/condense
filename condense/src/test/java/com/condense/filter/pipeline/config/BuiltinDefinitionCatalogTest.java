package com.condense.filter.pipeline.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinDefinitionCatalogTest {

    @Test
    void loadsThirtyOneDefinitionsFromIndexWithoutWalking() {
        BuiltinDefinitionCatalog catalog = BuiltinDefinitionCatalog.standalone();
        assertThat(catalog.names()).hasSize(31);
        assertThat(catalog.names()).doesNotContain("python");
        assertThat(catalog.requiredPipeline("pytest")).isNotNull();
        assertThat(catalog.requiredDefinition("npm-install").commands())
            .containsExactly("npm install", "npm ci", "npm i");
    }
}
