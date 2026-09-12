package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.AnsiStripStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailOpenGuaranteeTest {

    @Test
    @DisplayName("Option A verification: stage failure aborts pipeline immediately to raw passthrough")
    void stageFailureAbortsPipelineAndTriggersRawPassthrough_OptionA() {
        FilterStage goodStage = (input, ctx) -> StageResult.continueWith(input + " [stage1]");
        FilterStage explodingStage = new FilterStage() {
            @Override
            public String stageId() {
                return "exploding_stage";
            }

            @Override
            public StageResult process(String input, FilterContext context) {
                throw new RuntimeException("Simulated catastrophic stage failure");
            }
        };

        FilterPipeline pipeline = FilterPipeline.of(goodStage, explodingStage);

        // Direct pipeline execution must throw PipelineExecutionException (Option A: fail-open abort)
        assertThatThrownBy(() -> pipeline.execute("raw-input", FilterContext.empty()))
            .isInstanceOf(PipelineExecutionException.class)
            .hasMessageContaining("exploding_stage")
            .hasMessageContaining("Simulated catastrophic stage failure");
    }

    @Test
    @DisplayName("Multi-stage docker-build failure scenario: stage failure preserves stderr and marks wasFiltered=false")
    void multiStageFailure_preservesCriticalStderr_andTruthfulWasFiltered() {
        // Construct realistic 2000-line stdout
        StringBuilder stdoutBuilder = new StringBuilder();
        for (int i = 1; i <= 2000; i++) {
            stdoutBuilder.append("#").append(i).append(" [internal] load build definition from Dockerfile\n");
        }
        String stdout = stdoutBuilder.toString();
        String criticalStderr = "ERROR: failed to solve: process '/bin/sh -c npm run build' did not complete successfully: exit code: 1\n";

        ExecutionResult executionResult = new ExecutionResult(1, stdout, criticalStderr, 4500L);

        // Stage 1: ANSI strip (succeeds)
        FilterStage stage1 = AnsiStripStrategy.INSTANCE;

        // Stage 2: Summary stage that throws halfway through
        FilterStage explodingStage = new FilterStage() {
            @Override
            public String stageId() {
                return "docker_build_summary";
            }

            @Override
            public StageResult process(String input, FilterContext context) {
                throw new IllegalStateException("Simulated regex backtrack timeout in summary stage");
            }
        };

        FilterPipeline pipeline = FilterPipeline.of(stage1, explodingStage);

        // Test filter subclass using this pipeline
        PipelineBackedFilter testFilter = new PipelineBackedFilter(FilterOverrideLoader.standalone(), pipeline) {
            @Override
            protected String definitionName() {
                return "docker-build-test";
            }
        };

        FilterResult result = testFilter.apply(
            "docker build .",
            executionResult,
            CondenseConfig.defaults(),
            0,
            false
        );

        // Assertions:
        // 1. wasFiltered must be truthfully false
        assertThat(result.wasFiltered())
            .as("Result must truthfully report wasFiltered=false on stage failure")
            .isFalse();

        // 2. Output must contain the critical stderr error line, not stale intermediate output alone
        assertThat(result.output())
            .as("Fallback output must contain the critical error message from stderr")
            .contains("ERROR: failed to solve: process '/bin/sh -c npm run build'");

        // 3. Output must contain raw combined content
        assertThat(result.output())
            .as("Fallback output must retain the raw execution content")
            .contains("#1 [internal]");

        // 4. Incidents must record the stage failure
        assertThat(result.incidents())
            .isNotEmpty()
            .anyMatch(i -> i.kind().equals(FilterIncident.KIND_APPLY_FALLBACK)
                || i.kind().equals(FilterIncident.KIND_STAGE_EXCEPTION));
    }

    @Test
    @DisplayName("Filter boundary catches StackOverflowError and safely falls back to passthrough")
    void filterBoundary_catchesStackOverflowError_andFallsBackToPassthrough() {
        ExecutionResult executionResult = new ExecutionResult(1, "normal stdout", "critical error in stderr", 100L);

        FilterStage stackOverflowStage = new FilterStage() {
            @Override
            public String stageId() {
                return "recursive_parser_stage";
            }

            @Override
            public StageResult process(String input, FilterContext context) {
                throw new StackOverflowError("Simulated deep recursion in AST traversal");
            }
        };

        FilterPipeline pipeline = FilterPipeline.of(stackOverflowStage);

        PipelineBackedFilter testFilter = new PipelineBackedFilter(FilterOverrideLoader.standalone(), pipeline) {
            @Override
            protected String definitionName() {
                return "recursive-test";
            }
        };

        FilterResult result = testFilter.apply(
            "test-cmd",
            executionResult,
            CondenseConfig.defaults(),
            0,
            false
        );

        assertThat(result.wasFiltered())
            .as("Result must be marked wasFiltered=false on StackOverflowError")
            .isFalse();

        assertThat(result.output())
            .contains("critical error in stderr");
    }
}
