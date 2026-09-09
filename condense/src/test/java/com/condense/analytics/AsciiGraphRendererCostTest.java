package com.condense.analytics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AsciiGraphRendererCostTest {

    private final EstimatorInfo estimator = EstimatorInfo.current();

    private final ModelPricing sonnet = new ModelPricing(
        "claude-3-5-sonnet-20241022",
        List.of("sonnet"),
        "Claude 3.5 Sonnet",
        "Anthropic",
        3.00,
        15.00,
        0.30,
        3.75,
        "2024-10-22",
        "https://www.anthropic.com/pricing"
    );

    @Test
    void renderSummaryIncludesCostSavingsAndBasisWhenCostPresent() {
        CostEstimate cost = CostEstimate.calculate(sonnet, 100_000, 20_000, estimator);
        GainReport report = new GainReport(
            "global",
            30,
            12,
            100_000,
            20_000,
            80_000,
            80,
            500,
            41,
            List.of(),
            List.of(),
            estimator,
            cost,
            "homogeneous"
        );

        String output = AsciiGraphRenderer.renderSummary(report);
        assertThat(output).contains("Condense Token Savings (Global Scope)");
        assertThat(output).contains("Total commands:        12");
        assertThat(output).contains("Tokens saved (est.):   80,000 (80.0%)");
        assertThat(output).contains("Est. cost savings:     ~$0.24 USD (claude-3-5-sonnet-20241022 @ $3.00/M in)");
        assertThat(output).contains("Cost basis:            $3.00/M in, $15.00/M out (effective 2024-10-22)");
        assertThat(output).contains("Estimator:             utf8_weighted_v1  p95 ±37% vs cl100k_base");
        assertThat(output).contains("Uncertainty note:      Dollar figures inherit ±37% token estimation uncertainty");
        assertThat(output).doesNotContain("History note:");
    }

    @Test
    void renderSummaryShowsUnavailableWhenCostIsNull() {
        GainReport report = new GainReport(
            "global",
            30,
            5,
            10_000,
            2_000,
            8_000,
            80,
            100,
            20,
            List.of(),
            List.of(),
            estimator,
            null,
            "homogeneous"
        );

        String output = AsciiGraphRenderer.renderSummary(report);
        assertThat(output).contains("Est. cost savings:     (pricing unavailable - see --list-models)");
        assertThat(output).contains("Uncertainty note:      Dollar figures inherit ±37% token estimation uncertainty");
    }

    @Test
    void renderSummaryIncludesHistoryNoteWhenMixedEstimatorsPresent() {
        GainReport report = new GainReport(
            "global",
            30,
            25,
            500_000,
            100_000,
            400_000,
            80,
            2000,
            80,
            List.of(),
            List.of(),
            estimator,
            CostEstimate.calculate(sonnet, 500_000, 100_000, estimator),
            "mixed_estimators"
        );

        String output = AsciiGraphRenderer.renderSummary(report);
        assertThat(output).contains("History note:          Query window spans multiple estimator versions");
    }
}
