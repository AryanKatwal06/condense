package com.condense.core;

import com.condense.corpus.CorpusCatalog;
import com.condense.corpus.CorpusRunner;
import com.condense.filter.node.NpmInstallFilter;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.PipelineMode;
import com.condense.trust.Provenance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capture path: {@link FilterPipeline#execute} plus provenance equals {@code apply()}.
 * Stream path: {@link StreamingProxy#replay} (the live session chain) equals {@code apply()}.
 */
class IncrementalEquivalenceTest {

    @Test
    void captureExecuteMatchesApplyAfterProvenance() throws Exception {
        List<String> failures = compareCorpus(PipelineMode.CAPTURE);
        assertThat(failures)
            .as("CAPTURE execute+stamp drifted from apply")
            .isEmpty();
    }

    @Test
    void streamReplayMatchesApply() throws Exception {
        List<String> failures = compareCorpus(PipelineMode.STREAM);
        assertThat(failures)
            .as("STREAM replay drifted from apply")
            .isEmpty();
    }

    @Test
    void npmFailureWithWarnStillFiltersAndReplayMatchesApply() {
        NpmInstallFilter filter = new NpmInstallFilter();
        String raw = "npm warn deprecated foo@1.0.0: gone\nnpm ERR! code EFAIL\n";
        ExecutionResult execution = new ExecutionResult(1, raw, "", 10L);
        FilterResult applied = filter.apply("npm install", execution, CondenseConfig.defaults(), 0, false);
        assertThat(applied.wasFiltered()).isTrue();
        assertThat(applied.output()).startsWith(Provenance.STAMP);

        String selected = selectedInput(execution);
        FilterContext ctx = FilterContext.of("npm install", execution, CondenseConfig.defaults(), 0, false);
        String streamed = StreamingProxy.replay(filter.resolveActivePipeline("npm install"), selected, ctx);
        assertThat(streamed).isEqualTo(applied.output());
    }

    @Test
    void npmFailureWithoutWarnPassesThrough() {
        NpmInstallFilter filter = new NpmInstallFilter();
        String raw = "a leftover progress bar\nand nothing else\n";
        ExecutionResult execution = new ExecutionResult(1, raw, "", 10L);
        FilterResult applied = filter.apply("npm install", execution, CondenseConfig.defaults(), 0, false);
        assertThat(applied.wasFiltered()).isFalse();
        assertThat(applied.output()).isEqualTo(FilterResult.passthrough(execution).output());
        assertThat(applied.output()).doesNotStartWith(Provenance.STAMP);
    }

    private static List<String> compareCorpus(PipelineMode wanted) throws Exception {
        CorpusCatalog.Catalog catalog = CorpusCatalog.load();
        CondenseConfig config = CondenseConfig.defaults();
        List<String> failures = new ArrayList<>();
        int compared = 0;

        for (CorpusCatalog.Entry entry : catalog.entries()) {
            FilterStrategy filter = CorpusCatalog.instantiateForCommand(entry.command());
            if (!(filter instanceof PipelineBackedFilter pipelineFilter)) {
                continue;
            }
            FilterPipeline pipeline = pipelineFilter.resolveActivePipeline(entry.command());
            if (pipeline.mode() != wanted) {
                continue;
            }
            String fixture = CorpusRunner.loadFixture(entry.fixture());
            ExecutionResult execution = new ExecutionResult(entry.exitCode(), fixture, "", 10L);
            FilterResult gate = pipelineFilter.evaluateGate(entry.command(), execution, config, 0, false);
            FilterResult applied = pipelineFilter.apply(entry.command(), execution, config, 0, false);
            if (gate != null) {
                if (!applied.output().equals(gate.output())) {
                    failures.add(entry.id() + " apply did not equal gate");
                }
                compared++;
                continue;
            }
            String raw = selectedInput(execution);
            FilterContext ctx = FilterContext.of(entry.command(), execution, config, 0, false);
            String expected;
            if (wanted == PipelineMode.STREAM) {
                expected = StreamingProxy.replay(pipeline, raw, ctx);
            } else {
                expected = Provenance.stamp(pipeline.execute(raw, ctx));
            }
            compared++;
            if (!expected.equals(applied.output())) {
                failures.add(String.format(Locale.ROOT,
                    "%s %sLen=%d applyLen=%d",
                    entry.id(),
                    wanted == PipelineMode.STREAM ? "replay" : "stamped",
                    expected.length(),
                    applied.output().length()));
            }
        }

        assertThat(compared)
            .as("expected " + wanted + " corpus rows")
            .isGreaterThan(wanted == PipelineMode.STREAM ? 0 : 40);
        return failures;
    }

    /** Corpus and these gate fixtures put the body on stdout with empty stderr. */
    private static String selectedInput(ExecutionResult execution) {
        String stdout = execution.readStdout();
        return stdout.isBlank() ? execution.readStderr() : stdout;
    }
}
