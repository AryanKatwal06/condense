package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FilterIncidentCaptureTest {

    @Test
    void throwingStageKeepsPriorOutputAndRecordsIncident() {
        FilterContext context = FilterContext.of("pytest", null, CondenseConfig.defaults(), 0, false);
        FilterPipeline pipeline = FilterPipeline.of(
            (input, ctx) -> StageResult.continueWith("kept\n"),
            (input, ctx) -> {
                throw new RuntimeException("stage died");
            }
        );

        String output = pipeline.execute("raw", context);
        assertThat(output).isEqualTo("kept\n");
        assertThat(context.incidents()).hasSize(1);
        FilterIncident incident = context.incidents().get(0);
        assertThat(incident.kind()).isEqualTo(FilterIncident.KIND_STAGE_EXCEPTION);
        assertThat(incident.fallbackSucceeded()).isTrue();
        assertThat(incident.detail()).contains("stage died");
    }

    @Test
    void applyLevelThrowProducesFallbackIncident() {
        PipelineBackedFilter filter = new PipelineBackedFilter() {
            @Override
            protected String definitionName() {
                return "pytest";
            }

            @Override
            protected String selectInput(
                    String command,
                    ExecutionResult result,
                    CondenseConfig config,
                    int verbose,
                    boolean ultraCompact) {
                throw new RuntimeException("select exploded");
            }
        };

        FilterResult result = filter.apply(
            "pytest",
            new ExecutionResult(1, "stdout", "stderr", 5L),
            CondenseConfig.defaults(),
            0,
            false
        );
        assertThat(result.wasFiltered()).isFalse();
        assertThat(result.incidents()).hasSize(1);
        assertThat(result.incidents().get(0).kind()).isEqualTo(FilterIncident.KIND_APPLY_FALLBACK);
        assertThat(result.incidents().get(0).fallbackSucceeded()).isTrue();
        assertThat(result.incidents().get(0).filterName()).isNotBlank();
    }

    @Test
    void beforePipelinePassthroughProducesNoIncident() {
        ExecutionResult passing = new ExecutionResult(0, "===== 3 passed in 0.01s =====\n", "", 4L);
        PipelineBackedFilter gated = new PipelineBackedFilter() {
            @Override
            protected String definitionName() {
                return "pytest";
            }

            @Override
            protected FilterResult beforePipeline(
                    String command,
                    ExecutionResult exec,
                    CondenseConfig config,
                    int verbose,
                    boolean ultraCompact) {
                return FilterResult.passthrough(exec);
            }
        };
        FilterResult skipped = gated.apply("pytest", passing, CondenseConfig.defaults(), 0, false);
        assertThat(skipped.wasFiltered()).isFalse();
        assertThat(skipped.incidents()).isEmpty();
    }
}
