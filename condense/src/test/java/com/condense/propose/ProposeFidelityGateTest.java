package com.condense.propose;

import com.condense.corpus.CorpusCatalog;
import com.condense.corpus.CorpusRunner;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.config.BuiltinDefinition;
import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import com.condense.filter.pipeline.config.StageFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProposeFidelityGateTest {

    @Test
    void coverageCopyOfPnpmInstallKeepsCorpusSignals() throws Exception {
        BuiltinDefinition definition = BuiltinDefinitionCatalog.standalone().requiredDefinition("pnpm-install");
        FilterPipeline pipeline = StageFactory.buildPipeline(definition.stages());
        CorpusCatalog.Catalog catalog = CorpusCatalog.load();
        for (CorpusCatalog.Entry entry : catalog.entries()) {
            if (!"pnpm install".equals(entry.command())) {
                continue;
            }
            String input = CorpusRunner.loadFixture(entry.fixture());
            String output = pipeline.execute(input, FilterContext.empty());
            for (String signal : entry.criticalSignals()) {
                assertThat(output)
                    .as(entry.id() + " must keep " + signal)
                    .contains(signal);
            }
        }
    }

    @Test
    void coverageCopyOfPrismaKeepsCorpusSignals() throws Exception {
        BuiltinDefinition definition = BuiltinDefinitionCatalog.standalone().requiredDefinition("prisma");
        FilterPipeline pipeline = StageFactory.buildPipeline(definition.stages());
        CorpusCatalog.Catalog catalog = CorpusCatalog.load();
        for (CorpusCatalog.Entry entry : catalog.entries()) {
            if (!entry.command().startsWith("prisma ")) {
                continue;
            }
            String input = CorpusRunner.loadFixture(entry.fixture());
            String output = pipeline.execute(input, FilterContext.empty());
            for (String signal : entry.criticalSignals()) {
                assertThat(output)
                    .as(entry.id() + " must keep " + signal)
                    .contains(signal);
            }
        }
    }
}
