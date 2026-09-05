package com.condense.filter.pipeline;

import com.condense.filter.pipeline.config.BuiltinDefinitionCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamabilityDeriveTest {

    @Test
    void emptyPipelineIsStream() {
        assertThat(FilterPipeline.builder().build().mode()).isEqualTo(PipelineMode.STREAM);
    }

    @Test
    void oneDocumentStageForcesCapture() {
        FilterStage local = new OrderLocalStub();
        FilterStage document = (input, ctx) -> StageResult.continueWith(input);
        FilterPipeline pipeline = FilterPipeline.of(local, document);
        assertThat(pipeline.mode()).isEqualTo(PipelineMode.CAPTURE);
        assertThat(local.streamability()).isEqualTo(Streamability.ORDER_LOCAL);
        assertThat(document.streamability()).isEqualTo(Streamability.DOCUMENT);
    }

    @Test
    void allOrderLocalIsStream() {
        FilterPipeline pipeline = FilterPipeline.of(new OrderLocalStub(), new OrderLocalStub());
        assertThat(pipeline.mode()).isEqualTo(PipelineMode.STREAM);
    }

    @Test
    void namedStageForwardsDelegateStreamability() {
        FilterStage wrapped = NamedStage.wrap("ansi_strip", new OrderLocalStub());
        assertThat(wrapped.streamability()).isEqualTo(Streamability.ORDER_LOCAL);
        assertThat(FilterPipeline.of(wrapped).mode()).isEqualTo(PipelineMode.STREAM);
    }

    @Test
    void finalizeOnlyForcesCapture() {
        FilterStage tail = new FinalizeOnlyStub();
        assertThat(FilterPipeline.of(new OrderLocalStub(), tail).mode()).isEqualTo(PipelineMode.CAPTURE);
    }

    @Test
    void builtinSummariesAreCaptureUntilConverted() {
        BuiltinDefinitionCatalog catalog = BuiltinDefinitionCatalog.standalone();
        assertThat(catalog.requiredPipeline("npm-install").mode()).isEqualTo(PipelineMode.CAPTURE);
        assertThat(catalog.requiredPipeline("docker-build").mode()).isEqualTo(PipelineMode.CAPTURE);
        assertThat(catalog.requiredPipeline("docker-logs").mode()).isEqualTo(PipelineMode.CAPTURE);
        assertThat(catalog.requiredPipeline("git-status").mode()).isEqualTo(PipelineMode.CAPTURE);
    }

    private static final class OrderLocalStub implements FilterStage {
        @Override
        public StageResult process(String input, FilterContext context) {
            return StageResult.continueWith(input == null ? "" : input);
        }

        @Override
        public Streamability streamability() {
            return Streamability.ORDER_LOCAL;
        }
    }

    private static final class FinalizeOnlyStub implements FilterStage {
        @Override
        public StageResult process(String input, FilterContext context) {
            return StageResult.continueWith(input == null ? "" : input);
        }

        @Override
        public Streamability streamability() {
            return Streamability.FINALIZE_ONLY;
        }
    }
}
