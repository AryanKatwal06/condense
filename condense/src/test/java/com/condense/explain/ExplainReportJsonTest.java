package com.condense.explain;

import com.condense.analytics.EstimatorInfo;
import com.condense.core.Mappers;
import com.condense.core.Utf8WeightedTokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainReportJsonTest {

    @Test
    void emptyCollectionsStayMutableAndSerialize() throws Exception {
        ExplainReport report = new ExplainReport(
            "pytest",
            "PytestFilter",
            "builtin",
            "classpath:filters/pytest.toml",
            new ArrayList<>(),
            new ExplainReport.Gate(false, null, null),
            1,
            1,
            1,
            1,
            0,
            0,
            1,
            1,
            false,
            new ArrayList<>(),
            new ExplainReport.ProvenanceInfo(false, null),
            "ok",
            EstimatorInfo.current(),
            new ArrayList<>(),
            0,
            true,
            "capture"
        );
        assertThat(report.skippedTiers()).isInstanceOf(ArrayList.class);
        assertThat(report.stages()).isInstanceOf(ArrayList.class);
        assertThat(report.incidents()).isInstanceOf(ArrayList.class);

        JsonNode json = Mappers.JSON.readTree(Mappers.JSON.writeValueAsString(report));
        assertThat(json.get("skipped_tiers").isArray()).isTrue();
        assertThat(json.get("stages").isArray()).isTrue();
        assertThat(json.get("incidents").isArray()).isTrue();
        assertThat(json.get("estimator").get("name").asText())
            .isEqualTo(Utf8WeightedTokenEstimator.NAME);
        assertThat(json.get("pipeline_mode").asText()).isEqualTo("capture");
    }
}
