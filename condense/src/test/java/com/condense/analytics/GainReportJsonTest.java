package com.condense.analytics;

import com.condense.core.Mappers;
import com.condense.core.Utf8WeightedTokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GainReportJsonTest {

    @Test
    void jsonIncludesEstimatorAndKeepsExistingFields() throws Exception {
        GainReport report = new GainReport(
            "global", 30, 3L, 1000L, 200L, 800L, 80, 12L, 4L, List.of(), List.of(), null);

        String json = Mappers.JSON.writeValueAsString(report);
        JsonNode root = Mappers.JSON.readTree(json);

        assertThat(root.get("total_commands").asLong()).isEqualTo(3);
        assertThat(root.get("input_tokens").asLong()).isEqualTo(1000);
        assertThat(root.get("output_tokens").asLong()).isEqualTo(200);
        assertThat(root.get("tokens_saved").asLong()).isEqualTo(800);
        assertThat(root.get("savings_pct").asInt()).isEqualTo(80);

        JsonNode estimator = root.get("estimator");
        assertThat(estimator.get("name").asText()).isEqualTo(Utf8WeightedTokenEstimator.NAME);
        assertThat(estimator.get("reference").asText()).isEqualTo(Utf8WeightedTokenEstimator.REFERENCE_TOKENIZER);
        assertThat(estimator.get("p95_rel_error").asDouble())
            .isEqualTo(Utf8WeightedTokenEstimator.PUBLISHED_P95_REL_ERROR);
    }
}
