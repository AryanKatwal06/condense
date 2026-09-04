package com.condense.trust;

import com.condense.filter.pipeline.config.FilterOverrideConfig;
import com.condense.filter.pipeline.config.StageFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityPolicyTest {

    @Test
    void stageFactoryMapsCanonicalAliases() {
        assertThat(StageFactory.capabilityOf("ansi_strip")).isEqualTo(Capability.REDUCE);
        assertThat(StageFactory.capabilityOf("tail-lines")).isEqualTo(Capability.REDUCE);
        assertThat(StageFactory.capabilityOf("pytest_summary")).isEqualTo(Capability.RESHAPE);
        assertThat(StageFactory.capabilityOf("json_structure")).isEqualTo(Capability.RESHAPE);
        assertThat(StageFactory.capabilityOf("regex_capture")).isEqualTo(Capability.REWRITE);
        assertThat(StageFactory.capabilityOf("state_machine")).isEqualTo(Capability.REWRITE);
    }

    @Test
    void emptyStagesRequireReduce() {
        assertThat(StageFactory.requiredCapabilities(List.of())).containsExactly(Capability.REDUCE);
    }

    @Test
    void riskReportListsRewriteAndCatchAll() {
        FilterOverrideConfig.FileConfig config = new FilterOverrideConfig.FileConfig(
            1,
            Map.of("ls", new FilterOverrideConfig.FilterDef(List.of(
                new FilterOverrideConfig.StageDef(
                    "regex_capture", null, ".*", null, null, List.of(), Map.of(),
                    null, null, null, null, null, null, null, null, "$1", "")
            )))
        );
        FilterRisk.Report report = FilterRisk.classify(config);
        assertThat(report.required()).contains(Capability.REWRITE);
        assertThat(report.rewriteStages()).contains("regex_capture");
        assertThat(report.catchAllRegexes()).contains(".*");
    }
}
