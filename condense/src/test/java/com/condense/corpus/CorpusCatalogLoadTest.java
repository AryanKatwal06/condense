package com.condense.corpus;

import com.condense.core.FilterStrategy;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorpusCatalogLoadTest {

    @Test
    void loadRejectsUnknownKeys() {
        String json = """
            {
              "schema_version": 1,
              "garbage": true,
              "entries": [
                {
                  "id": "x",
                  "command": "pytest",
                  "fixture": "fixtures/pytest/typical.txt",
                  "exit_code": 1,
                  "savings_floor": 60,
                  "critical_signals": ["failed"]
                }
              ]
            }
            """;
        assertThatThrownBy(() -> CorpusCatalog.parse(json))
            .hasMessageContaining("garbage");
    }

    @Test
    void loadRejectsMissingSchemaVersion() {
        String json = """
            {
              "entries": [
                {
                  "id": "x",
                  "command": "pytest",
                  "fixture": "fixtures/pytest/typical.txt",
                  "exit_code": 1,
                  "savings_floor": 60,
                  "critical_signals": ["failed"]
                }
              ]
            }
            """;
        assertThatThrownBy(() -> CorpusCatalog.parse(json))
            .hasMessageContaining("schema_version");
    }

    @Test
    void loadRejectsWrongSchemaVersion() {
        String json = """
            {
              "schema_version": 99,
              "entries": [
                {
                  "id": "x",
                  "command": "pytest",
                  "fixture": "fixtures/pytest/typical.txt",
                  "exit_code": 1,
                  "savings_floor": 60,
                  "critical_signals": ["failed"]
                }
              ]
            }
            """;
        assertThatThrownBy(() -> CorpusCatalog.parse(json))
            .hasMessageContaining("schema_version");
    }

    @Test
    void loadRejectsMissingFloorAndExemption() {
        String json = """
            {
              "schema_version": 1,
              "entries": [
                {
                  "id": "x",
                  "command": "pytest",
                  "fixture": "fixtures/pytest/typical.txt",
                  "exit_code": 1,
                  "critical_signals": ["failed"]
                }
              ]
            }
            """;
        assertThatThrownBy(() -> CorpusCatalog.parse(json))
            .hasMessageContaining("savings_floor");
    }

    @Test
    void loadAcceptsExemptionWithoutFloor() throws Exception {
        String json = """
            {
              "schema_version": 1,
              "entries": [
                {
                  "id": "python-c/typical",
                  "command": "python -c",
                  "fixture": "fixtures/python-c/typical.txt",
                  "exit_code": 0,
                  "savings_exemption": "intentional_identity",
                  "critical_signals": ["hello"]
                }
              ]
            }
            """;
        CorpusCatalog.Catalog catalog = CorpusCatalog.parse(json);
        assertThat(catalog.schemaVersion()).isEqualTo(1);
        assertThat(catalog.entries()).hasSize(1);
        assertThat(catalog.entries().get(0).savingsExemption())
            .isEqualTo(CorpusCatalog.SavingsExemption.INTENTIONAL_IDENTITY);
        assertThat(catalog.entries().get(0).claimsToCompress()).isFalse();
    }

    @Test
    void domainFilterDiscoveryExcludesPassthrough() throws Exception {
        Set<Class<?>> types = CorpusCatalog.discoverDomainFilters();
        assertThat(types)
            .as("32 domain filters, no PassthroughStrategy")
            .hasSize(32);
        assertThat(types.stream().map(Class::getSimpleName).toList())
            .doesNotContain("PassthroughStrategy");
        assertThat(types).allMatch(FilterStrategy.class::isAssignableFrom);
        Set<String> names = new LinkedHashSet<>();
        types.forEach(t -> names.add(t.getName()));
        assertThat(names).hasSize(32);
    }
}
