package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
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
    @DisplayName("First stage short-circuit returns immediately without running subsequent stages")
    void firstStage_shortCircuit_returnsImmediately() {
        List<String> executionOrder = new ArrayList<>();

        FilterStage shortCircuitStage = (input, ctx) -> {
            executionOrder.add("stage1-shortcircuit");
            return StageResult.stopWith("first-stage-output");
        };

        FilterStage stage2 = (input, ctx) -> {
            executionOrder.add("stage2-should-not-run");
            return StageResult.continueWith("stage2-output");
        };

        FilterPipeline pipeline = FilterPipeline.of(shortCircuitStage, stage2);
        String result = pipeline.execute("raw-input");

        assertThat(result).isEqualTo("first-stage-output");
        assertThat(executionOrder).containsExactly("stage1-shortcircuit");
    }

    @Test
    @DisplayName("Last stage short-circuit returns correctly")
    void lastStage_shortCircuit_returnsLastStageOutput() {
        FilterStage stage1 = (input, ctx) -> StageResult.continueWith(input + "-stage1");
        FilterStage stage2 = (input, ctx) -> StageResult.stopWith(input + "-stage2-stop");

        FilterPipeline pipeline = FilterPipeline.of(stage1, stage2);
        String result = pipeline.execute("start");

        assertThat(result).isEqualTo("start-stage1-stage2-stop");
    }

    @Test
    @DisplayName("Stage throwing an unchecked exception fails open and continues with previous output")
    void stageThrowsException_failsOpenAndContinues() {
        FilterStage stage1 = (input, ctx) -> StageResult.continueWith("stage1-clean");
        FilterStage throwingStage = (input, ctx) -> {
            throw new RuntimeException("Simulated parser failure");
        };
        FilterStage stage3 = (input, ctx) -> StageResult.continueWith(input + "-stage3");

        FilterPipeline pipeline = FilterPipeline.of(stage1, throwingStage, stage3);
        String result = pipeline.execute("start");

        assertThat(result).isEqualTo("stage1-clean-stage3");
    }

    @Test
    @DisplayName("Stage returning null StageResult is skipped safely")
    void stageReturnsNull_skippedSafely() {
        FilterStage stage1 = (input, ctx) -> StageResult.continueWith("stage1-ok");
        FilterStage nullStage = (input, ctx) -> null;
        FilterStage stage3 = (input, ctx) -> StageResult.continueWith(input + "-stage3");

        FilterPipeline pipeline = FilterPipeline.of(stage1, nullStage, stage3);
        String result = pipeline.execute("start");

        assertThat(result).isEqualTo("stage1-ok-stage3");
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
    @DisplayName("FilterPipeline Builder supports chaining addStage and addStages")
    void builder_supportsChainingAndAddStages() {
        FilterStage s1 = (in, ctx) -> StageResult.continueWith(in + "1");
        FilterStage s2 = (in, ctx) -> StageResult.continueWith(in + "2");
        FilterStage s3 = (in, ctx) -> StageResult.continueWith(in + "3");

        FilterPipeline pipeline = FilterPipeline.builder()
            .addStage(s1)
            .addStages(s2, s3)
            .build();

        assertThat(pipeline.size()).isEqualTo(3);
        assertThat(pipeline.execute("0")).isEqualTo("0123");
    }

    @Test
    @DisplayName("FilterPipeline Builder rejects null in addStages")
    void builder_addStages_rejectsNull() {
        assertThatThrownBy(() -> FilterPipeline.builder().addStages(new FilterStage[]{ null }))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("stage must not be null");
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
