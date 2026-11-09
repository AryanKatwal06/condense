package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NetWinGateTest {

    @Test
    void detectsCharacterAndTokenInflation() {
        String raw = "alpha\nbeta\ngamma\n";

        assertThat(NetWinGate.isNetLoss(raw, raw.repeat(3), false)).isTrue();
    }

    @Test
    void additiveEnrichmentIsStillMeasuredByTheGateItself() {
        String raw = "alpha\nbeta\n";

        assertThat(NetWinGate.isNetLoss(raw, "header\n" + raw + "footer\n", false)).isTrue();
    }

    @Test
    void shorterOrEqualOutputIsNotALoss() {
        String raw = "alpha\nbeta\ngamma\n";

        assertThat(NetWinGate.isNetLoss(raw, "alpha\n", false)).isFalse();
        assertThat(NetWinGate.isNetLoss(raw, raw, false)).isFalse();
    }

    @Test
    void nullAndEmptyInputsAreNotALoss() {
        assertThat(NetWinGate.isNetLoss(null, "anything", false)).isFalse();
        assertThat(NetWinGate.isNetLoss("", "anything", false)).isFalse();
        assertThat(NetWinGate.isNetLoss("raw", null, false)).isFalse();
    }

    @Test
    void ultraCompactDisablesTheGate() {
        String raw = "alpha\nbeta\n";

        assertThat(NetWinGate.isNetLoss(raw, raw.repeat(3), true)).isFalse();
    }

    @Test
    void pipelineFallsBackWhenFilterInflatesOutput() {
        FilterStage inflating = (input, ctx) -> StageResult.continueWith(input.repeat(3));
        PipelineBackedFilter filter = new PipelineBackedFilter(
            FilterOverrideLoader.standalone(), FilterPipeline.of(inflating)) {
            @Override
            protected String definitionName() {
                return "net-win-test";
            }
        };
        String raw = "alpha\nbeta\ngamma\ndelta\n";

        FilterResult result = filter.apply(
            "test-cmd",
            new ExecutionResult(0, raw, "", 10L),
            CondenseConfig.defaults(),
            0,
            false);

        assertThat(result.wasFiltered()).isFalse();
        assertThat(result.output()).isEqualTo(raw);
    }
}
