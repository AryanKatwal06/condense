package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FilterPipelineTraceTest {

    @Test
    void emptyPipelineIsIdentity() {
        FilterPipeline pipeline = FilterPipeline.builder().build();
        PipelineTrace trace = pipeline.executeTraced("hello", FilterContext.empty());
        assertThat(trace.output()).isEqualTo("hello");
        assertThat(trace.stages()).isEmpty();
        assertThat(pipeline.execute("hello")).isEqualTo(trace.output());
    }

    @Test
    void nullInputBecomesEmptyString() {
        FilterPipeline pipeline = FilterPipeline.builder().build();
        assertThat(pipeline.execute(null)).isEqualTo("");
        assertThat(pipeline.executeTraced(null, null).output()).isEqualTo("");
    }

    @Test
    void stagesRunInOrderAndExecuteMatchesTrace() {
        FilterPipeline pipeline = FilterPipeline.of(
            NamedStage.wrap("one", (input, ctx) -> StageResult.continueWith(input + "-1")),
            NamedStage.wrap("two", (input, ctx) -> StageResult.continueWith(input + "-2"))
        );
        FilterContext ctx = FilterContext.of("cmd", null, CondenseConfig.defaults(), 0, false);
        String executed = pipeline.execute("start", ctx);
        PipelineTrace trace = pipeline.executeTraced("start", FilterContext.of("cmd", null, CondenseConfig.defaults(), 0, false));
        assertThat(trace.output()).isEqualTo(executed).isEqualTo("start-1-2");
        assertThat(trace.stages()).extracting(StageTrace::id).containsExactly("one", "two");
        assertThat(trace.stages()).extracting(StageTrace::status)
            .containsExactly(StageTrace.RAN, StageTrace.RAN);
    }

    @Test
    void shortCircuitMarksLaterStagesSkipped() {
        FilterPipeline pipeline = FilterPipeline.of(
            NamedStage.wrap("first", (input, ctx) -> StageResult.continueWith("kept")),
            NamedStage.wrap("stop", (input, ctx) -> StageResult.stopWith("done")),
            NamedStage.wrap("never", (input, ctx) -> StageResult.continueWith("nope"))
        );
        PipelineTrace trace = pipeline.executeTraced("raw", FilterContext.empty());
        assertThat(trace.output()).isEqualTo("done");
        assertThat(trace.shortCircuited()).isTrue();
        assertThat(trace.stages()).extracting(StageTrace::status)
            .containsExactly(StageTrace.RAN, StageTrace.SHORT_CIRCUITED, StageTrace.SKIPPED);
        assertThat(pipeline.execute("raw")).isEqualTo(trace.output());
    }

    @Test
    void throwingStageRecordsExceptionAndContinues() {
        FilterContext ctx = FilterContext.of("pytest", null, CondenseConfig.defaults(), 0, false);
        FilterPipeline pipeline = FilterPipeline.of(
            NamedStage.wrap("keep", (input, c) -> StageResult.continueWith("kept\n")),
            NamedStage.wrap("boom", (input, c) -> {
                throw new RuntimeException("stage died");
            }),
            NamedStage.wrap("after", (input, c) -> StageResult.continueWith(input + "after"))
        );
        PipelineTrace trace = pipeline.executeTraced("raw", ctx);
        assertThat(trace.output()).isEqualTo("kept\nafter");
        assertThat(trace.stages().get(1).status()).isEqualTo(StageTrace.EXCEPTION);
        assertThat(trace.stages().get(1).detail()).contains("stage died");
        assertThat(ctx.incidents()).hasSize(1);
        assertThat(ctx.incidents().get(0).stageName()).isEqualTo("boom");
        assertThat(pipeline.execute("raw", FilterContext.of("pytest", null, CondenseConfig.defaults(), 0, false)))
            .isEqualTo(trace.output());
    }

    @Test
    void sampleLimitZeroKeepsCountsOnly() {
        FilterPipeline pipeline = FilterPipeline.of(
            NamedStage.wrap("drop", (input, ctx) -> StageResult.continueWith("only"))
        );
        PipelineTrace trace = pipeline.executeTraced("a\nb\nc", FilterContext.empty(), 0);
        StageTrace stage = trace.stages().getFirst();
        assertThat(stage.droppedLines()).isEqualTo(3);
        assertThat(stage.addedLines()).isEqualTo(1);
        assertThat(stage.droppedSample()).isEmpty();
        assertThat(stage.droppedTruncated()).isTrue();
    }

    @Test
    void lineIdentityTelescopes() {
        FilterPipeline pipeline = FilterPipeline.of(
            NamedStage.wrap("a", (input, ctx) -> StageResult.continueWith("x\ny")),
            NamedStage.wrap("b", (input, ctx) -> StageResult.continueWith("x"))
        );
        String raw = "one\ntwo\nthree";
        PipelineTrace trace = pipeline.executeTraced(raw, FilterContext.empty());
        int net = 0;
        for (StageTrace stage : trace.stages()) {
            net += stage.droppedLines() - stage.addedLines();
            assertThat(stage.keptLines() + stage.droppedLines()).isEqualTo(stage.inputLines());
            assertThat(stage.keptLines() + stage.addedLines()).isEqualTo(stage.outputLines());
        }
        LineDiff total = LineDiff.of(raw, trace.output());
        assertThat(net).isEqualTo(total.inputLines() - total.outputLines());
    }
}
