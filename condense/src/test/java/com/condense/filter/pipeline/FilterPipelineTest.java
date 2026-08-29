package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilterPipelineTest {

    @Test
    @DisplayName("Empty pipeline returns input unchanged")
    void emptyPipeline_returnsInputUnchanged() {
        FilterPipeline pipeline = FilterPipeline.builder().build();

        assertThat(pipeline.isEmpty()).isTrue();
        assertThat(pipeline.size()).isZero();
        assertThat(pipeline.execute("hello world")).isEqualTo("hello world");
        assertThat(pipeline.execute("")).isEqualTo("");
        assertThat(pipeline.execute(null)).isEqualTo("");
    }

    @Test
    @DisplayName("Stages are applied in exact sequential order")
    void stages_appliedInSequentialOrder() {
        List<String> executionOrder = new ArrayList<>();

        FilterStage stage1 = (input, ctx) -> {
            executionOrder.add("stage1");
            return StageResult.continueWith(input + " -> stage1");
        };

        FilterStage stage2 = (input, ctx) -> {
            executionOrder.add("stage2");
            return StageResult.continueWith(input + " -> stage2");
        };

        FilterStage stage3 = (input, ctx) -> {
            executionOrder.add("stage3");
            return StageResult.continueWith(input + " -> stage3");
        };

        FilterPipeline pipeline = FilterPipeline.of(stage1, stage2, stage3);

        String result = pipeline.execute("start");

        assertThat(result).isEqualTo("start -> stage1 -> stage2 -> stage3");
        assertThat(executionOrder).containsExactly("stage1", "stage2", "stage3");
    }

    @Test
    @DisplayName("Short-circuiting stage halts pipeline execution immediately")
    void shortCircuit_haltsExecution() {
        List<String> executionOrder = new ArrayList<>();

        FilterStage stage1 = (input, ctx) -> {
            executionOrder.add("stage1");
            return StageResult.continueWith("processed-by-1");
        };

        FilterStage shortCircuitStage = (input, ctx) -> {
            executionOrder.add("stage2-shortcircuit");
            return StageResult.stopWith("early-exit-result");
        };

        FilterStage stage3 = (input, ctx) -> {
            executionOrder.add("stage3-should-not-run");
            return StageResult.continueWith("should-never-happen");
        };

        FilterPipeline pipeline = FilterPipeline.builder()
            .addStage(stage1)
            .addStage(shortCircuitStage)
            .addStage(stage3)
            .build();

        String result = pipeline.execute("raw-input");

        assertThat(result).isEqualTo("early-exit-result");
        assertThat(executionOrder).containsExactly("stage1", "stage2-shortcircuit");
    }

    @Test
    @DisplayName("FilterContext passes metadata through the pipeline")
    void filterContext_passesMetadata() {
        CondenseConfig config = CondenseConfig.defaults();
        ExecutionResult execResult = new ExecutionResult(0, "stdout", "stderr", 15L);
        FilterContext context = FilterContext.of("git status", execResult, config, 2, true);

        FilterStage stage = (input, ctx) -> {
            assertThat(ctx.command()).isEqualTo("git status");
            assertThat(ctx.verbose()).isEqualTo(2);
            assertThat(ctx.ultraCompact()).isTrue();
            assertThat(ctx.result()).isSameAs(execResult);
            assertThat(ctx.config()).isSameAs(config);
            return StageResult.continueWith("context-validated");
        };

        FilterPipeline pipeline = FilterPipeline.of(stage);
        String result = pipeline.execute("input", context);
        assertThat(result).isEqualTo("context-validated");
    }

    @Test
    @DisplayName("execute(ExecutionResult) wraps output in FilterResult")
    void executeWithExecutionResult_wrapsInFilterResult() {
        ExecutionResult execResult = new ExecutionResult(0, "raw text output", "", 20L);
        FilterStage stage = (input, ctx) -> StageResult.continueWith("compressed output");

        FilterPipeline pipeline = FilterPipeline.of(stage);
        FilterResult filterResult = pipeline.execute(execResult, FilterContext.empty());

        assertThat(filterResult.output()).isEqualTo("compressed output");
        assertThat(filterResult.wasFiltered()).isTrue();
        assertThat(filterResult.rawTokens()).isPositive();
    }

    @Test
    @DisplayName("Null stage rejection during construction")
    void nullStageRejection() {
        assertThatThrownBy(() -> FilterPipeline.builder().addStage(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("stage must not be null");

        assertThatThrownBy(() -> new FilterPipeline(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("stages must not be null");
    }

    @Test
    @DisplayName("FilterPipeline stages list is immutable")
    void stagesList_isImmutable() {
        FilterStage stage = (input, ctx) -> StageResult.continueWith(input);
        FilterPipeline pipeline = FilterPipeline.of(stage);

        assertThatThrownBy(() -> pipeline.stages().add(stage))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
