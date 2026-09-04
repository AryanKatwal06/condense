package com.condense.corpus;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusCoverageTest {

    @Test
    void everyDomainFilterHasAtLeastOneCatalogEntry() throws Exception {
        CorpusCatalog.Catalog catalog = CorpusCatalog.load();
        Map<String, Class<?>> prefixes = CorpusCatalog.prefixIndex();
        Set<Class<?>> covered = new LinkedHashSet<>();
        for (CorpusCatalog.Entry entry : catalog.entries()) {
            covered.add(CorpusCatalog.resolveFilterClass(entry.command(), prefixes));
        }

        Set<Class<?>> expected = CorpusCatalog.discoverDomainFilters();
        assertThat(expected).hasSize(32);
        assertThat(covered)
            .as("every FilterStrategy except PassthroughStrategy must have ≥1 catalog row")
            .containsAll(expected);
    }

    @Test
    void newCompressingEntriesMustMeetContributionBar() throws Exception {
        CorpusCatalog.Catalog catalog = CorpusCatalog.load();
        for (CorpusCatalog.Entry entry : catalog.entries()) {
            if (!entry.requiresContributionBar()) {
                continue;
            }
            assertThat(entry.savingsFloor())
                .as("%s claims to compress and is not grandfathered, so savings_floor must be ≥ 60", entry.id())
                .isGreaterThanOrEqualTo(60);
        }
    }

    @Test
    void everyFixtureResourceExists() throws Exception {
        CorpusCatalog.Catalog catalog = CorpusCatalog.load();
        for (CorpusCatalog.Entry entry : catalog.entries()) {
            assertThat(CorpusRunner.loadFixture(entry.fixture()))
                .as("%s fixture %s", entry.id(), entry.fixture())
                .isNotNull();
        }
    }
}
