package com.condense.analytics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CostEstimateTest {

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
    void standardCalculationProducesExpectedUsdValues() {
        EstimatorInfo estimator = EstimatorInfo.current();
        CostEstimate cost = CostEstimate.calculate(sonnet, 1_000_000, 200_000, estimator);

        assertThat(cost).isNotNull();
        assertThat(cost.model()).isEqualTo("claude-3-5-sonnet-20241022");
        assertThat(cost.provider()).isEqualTo("Anthropic");
        assertThat(cost.currency()).isEqualTo("USD");
        assertThat(cost.inputRatePerM()).isEqualTo(3.00);
        assertThat(cost.outputRatePerM()).isEqualTo(15.00);
        assertThat(cost.cacheReadRatePerM()).isEqualTo(0.30);
        assertThat(cost.estimatedRawUsd()).isEqualTo(3.00);
        assertThat(cost.estimatedFilteredUsd()).isEqualTo(0.60);
        assertThat(cost.estimatedUsdSaved()).isEqualTo(2.40);
        assertThat(cost.pricingEffectiveDate()).isEqualTo("2024-10-22");
        assertThat(cost.pricingSource()).isEqualTo("https://www.anthropic.com/pricing");
        assertThat(cost.uncertainty()).contains("±37%");
        assertThat(cost.uncertainty()).contains("utf8_weighted_v1");
    }

    @Test
    void zeroTokensYieldsZeroDollars() {
        CostEstimate cost = CostEstimate.calculate(sonnet, 0, 0, EstimatorInfo.current());
        assertThat(cost).isNotNull();
        assertThat(cost.estimatedRawUsd()).isZero();
        assertThat(cost.estimatedFilteredUsd()).isZero();
        assertThat(cost.estimatedUsdSaved()).isZero();
    }

    @Test
    void negativeTokenSavingsClampsCostSavedToZero() {
        CostEstimate cost = CostEstimate.calculate(sonnet, 500, 800, EstimatorInfo.current());
        assertThat(cost).isNotNull();
        assertThat(cost.estimatedRawUsd()).isGreaterThan(0.0);
        assertThat(cost.estimatedFilteredUsd()).isGreaterThan(cost.estimatedRawUsd());
        assertThat(cost.estimatedUsdSaved()).isZero();
    }

    @Test
    void nullPricingReturnsNull() {
        assertThat(CostEstimate.calculate(null, 1000, 500, EstimatorInfo.current())).isNull();
    }

    @Test
    void smallTokenCountsRetainSixDecimalPrecision() {
        // 80 saved tokens * $3.00 / 1M = $0.000240
        CostEstimate cost = CostEstimate.calculate(sonnet, 100, 20, EstimatorInfo.current());
        assertThat(cost).isNotNull();
        assertThat(cost.estimatedUsdSaved()).isEqualTo(0.000240);
        assertThat(cost.estimatedRawUsd()).isEqualTo(0.000300);
        assertThat(cost.estimatedFilteredUsd()).isEqualTo(0.000060);
    }

    @Test
    void customEstimatorErrorPropagatesToUncertaintyNote() {
        EstimatorInfo custom = new EstimatorInfo("custom_estimator", "reference_test", 0.22);
        CostEstimate cost = CostEstimate.calculate(sonnet, 1000, 500, custom);
        assertThat(cost).isNotNull();
        assertThat(cost.uncertainty()).isEqualTo("±22% (inherited from custom_estimator token estimator)");
    }
}
