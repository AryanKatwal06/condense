package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterStrategy;
import com.condense.corpus.CorpusCatalog;
import com.condense.corpus.CorpusRunner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Incremental session walk must match batch {@link FilterPipeline#execute}
 * for every corpus pipeline. DocumentSession makes this hold before any
 * stage grows a real incremental implementation.
 */
class IncrementalEquivalenceTest {

    @Test
    void sessionWalkMatchesExecuteForEveryCorpusPipeline() throws Exception {
        CorpusCatalog.Catalog catalog = CorpusCatalog.load();
        CondenseConfig config = CondenseConfig.defaults();
        List<String> failures = new ArrayList<>();
        int compared = 0;

        for (CorpusCatalog.Entry entry : catalog.entries()) {
            Class<?> type = CorpusCatalog.resolveFilterClass(entry.command());
            FilterStrategy filter = CorpusCatalog.instantiate(type);
            if (!(filter instanceof PipelineBackedFilter pipelineFilter)) {
                continue;
            }
            String fixture = CorpusRunner.loadFixture(entry.fixture());
            ExecutionResult execution = new ExecutionResult(entry.exitCode(), fixture, "", 10L);
            if (pipelineFilter.beforePipeline(entry.command(), execution, config, 0, false) != null) {
                continue;
            }
            String raw = pipelineFilter.selectInput(entry.command(), execution, config, 0, false);
            FilterContext batchCtx = FilterContext.of(entry.command(), execution, config, 0, false);
            FilterContext sessionCtx = FilterContext.of(entry.command(), execution, config, 0, false);
            FilterPipeline pipeline = pipelineFilter.defaultPipeline();
            String batch = pipeline.execute(raw, batchCtx);
            String incremental = pipeline.executeIncremental(raw, sessionCtx);
            compared++;
            if (!batch.equals(incremental)) {
                failures.add(String.format(Locale.ROOT,
                    "%s batchLen=%d incrementalLen=%d",
                    entry.id(), batch.length(), incremental.length()));
            }
        }

        assertThat(compared)
            .as("expected pipeline-backed corpus rows")
            .isGreaterThan(40);
        assertThat(failures)
            .as("session walk drifted from execute")
            .isEmpty();
    }

    @Test
    void sessionWalkMatchesExecuteOnShortCircuitAndException() {
        FilterPipeline pipeline = FilterPipeline.of(
            NamedStage.wrap("keep", (input, ctx) -> StageResult.continueWith("kept\n")),
            NamedStage.wrap("stop", (input, ctx) -> StageResult.stopWith("done")),
            NamedStage.wrap("never", (input, ctx) -> StageResult.continueWith("nope"))
        );
        assertThat(pipeline.executeIncremental("raw", FilterContext.empty()))
            .isEqualTo(pipeline.execute("raw"));

        FilterPipeline boom = FilterPipeline.of(
            NamedStage.wrap("keep", (input, ctx) -> StageResult.continueWith("kept\n")),
            NamedStage.wrap("boom", (input, ctx) -> {
                throw new RuntimeException("stage died");
            }),
            NamedStage.wrap("after", (input, ctx) -> StageResult.continueWith(input + "after"))
        );
        FilterContext batchCtx = FilterContext.of("cmd", null, CondenseConfig.defaults(), 0, false);
        FilterContext sessionCtx = FilterContext.of("cmd", null, CondenseConfig.defaults(), 0, false);
        assertThat(boom.executeIncremental("raw", sessionCtx)).isEqualTo(boom.execute("raw", batchCtx));
        assertThat(sessionCtx.incidents()).hasSize(1);
        assertThat(batchCtx.incidents()).hasSize(1);
    }
}
