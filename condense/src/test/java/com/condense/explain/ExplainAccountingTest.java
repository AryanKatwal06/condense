package com.condense.explain;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.core.FilterStrategy;
import com.condense.corpus.CorpusCatalog;
import com.condense.corpus.CorpusRunner;
import com.condense.filter.pipeline.PipelineBackedFilter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainAccountingTest {

    @Test
    void everyCorpusEntryHasMatchingApplyOutputAndLineIdentity() throws Exception {
        CorpusCatalog.Catalog catalog = CorpusCatalog.load();
        ExplainService service = new ExplainService();
        CondenseConfig config = CondenseConfig.defaults();
        List<String> failures = new ArrayList<>();
        StringBuilder table = new StringBuilder();
        table.append(String.format(Locale.ROOT,
            "%-32s %6s %8s %8s%n", "id", "stages", "dLines", "dTok"));

        for (CorpusCatalog.Entry entry : catalog.entries()) {
            Class<?> type = CorpusCatalog.resolveFilterClass(entry.command());
            FilterStrategy filter = CorpusCatalog.instantiate(type);
            String fixture = CorpusRunner.loadFixture(entry.fixture());
            ExecutionResult execution = new ExecutionResult(entry.exitCode(), fixture, "", 10L);
            FilterResult applied = CorpusRunner.apply(entry);
            ExplainReport report = service.explainStrategy(
                filter, entry.command(), execution, config, 0, false, 32, null);

            if (!applied.output().equals(report.filteredOutput())) {
                failures.add(entry.id() + " filtered_output != apply()");
            }
            if (filter instanceof PipelineBackedFilter && (report.gate() == null || !report.gate().fired())
                && !report.stages().isEmpty()) {
                int netLines = 0;
                int netTokens = 0;
                for (ExplainReport.Stage stage : report.stages()) {
                    if ("skipped".equals(stage.status())) {
                        continue;
                    }
                    netLines += stage.droppedLines() - stage.addedLines();
                    netTokens += stage.inputTokens() - stage.outputTokens();
                    if (stage.keptLines() + stage.droppedLines() != stage.inputLines()) {
                        failures.add(entry.id() + " " + stage.id() + " kept+dropped != input");
                    }
                    if (stage.keptLines() + stage.addedLines() != stage.outputLines()) {
                        failures.add(entry.id() + " " + stage.id() + " kept+added != output");
                    }
                }
                if (netLines != report.inputLines() - report.outputLines()) {
                    failures.add(entry.id() + " line identity "
                        + netLines + " != " + (report.inputLines() - report.outputLines()));
                }
                if (netTokens != report.inputTokens() - report.outputTokens()) {
                    failures.add(entry.id() + " token identity "
                        + netTokens + " != " + (report.inputTokens() - report.outputTokens()));
                }
            }
            table.append(String.format(Locale.ROOT, "%-32s %6d %8d %8d%n",
                entry.id(),
                report.stages().size(),
                report.lineDelta(),
                report.tokenDelta()));
        }

        System.out.print(table);
        assertThat(failures).as("explain accounting violations").isEmpty();
        assertThat(catalog.entries()).hasSize(51);
    }
}
