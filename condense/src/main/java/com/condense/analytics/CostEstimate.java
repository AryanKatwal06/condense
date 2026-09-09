package com.condense.analytics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Locale;

/**
 * Auditable cost estimate record. Every dollar estimate explicitly states the
 * model, provider, rates per million tokens, effective date, source URL,
 * and token estimator uncertainty.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record CostEstimate(

    @JsonProperty("model")
    String model,

    @JsonProperty("provider")
    String provider,

    @JsonProperty("currency")
    String currency,

    @JsonProperty("input_rate_per_m")
    double inputRatePerM,

    @JsonProperty("output_rate_per_m")
    double outputRatePerM,

    @JsonProperty("cache_read_rate_per_m")
    double cacheReadRatePerM,

    @JsonProperty("estimated_usd_saved")
    double estimatedUsdSaved,

    @JsonProperty("estimated_raw_usd")
    double estimatedRawUsd,

    @JsonProperty("estimated_filtered_usd")
    double estimatedFilteredUsd,

    @JsonProperty("pricing_effective_date")
    String pricingEffectiveDate,

    @JsonProperty("pricing_source")
    String pricingSource,

    @JsonProperty("uncertainty")
    String uncertainty

) {

    public static final String CURRENCY_USD = "USD";
    public static final String UNCERTAINTY_NOTE =
        "±37% (inherited from utf8_weighted_v1 token estimator)";

    public static CostEstimate calculate(
        ModelPricing pricing,
        long rawTokens,
        long filteredTokens,
        EstimatorInfo estimator
    ) {
        if (pricing == null) {
            return null;
        }

        double inputRate = pricing.inputCostPerMillion();
        double outputRate = pricing.outputCostPerMillion();
        double cacheReadRate = pricing.cacheReadCostPerMillion();

        long safeRaw = Math.max(0L, rawTokens);
        long safeFiltered = Math.max(0L, filteredTokens);
        long savedTokens = Math.max(0L, safeRaw - safeFiltered);

        double rawUsd = round6(safeRaw * (inputRate / 1_000_000.0));
        double filteredUsd = round6(safeFiltered * (inputRate / 1_000_000.0));
        double savedUsd = round6(savedTokens * (inputRate / 1_000_000.0));

        String uncertainty = estimator != null
            ? String.format(Locale.ROOT, "±%d%% (inherited from %s token estimator)",
                (int) Math.round(estimator.p95RelError() * 100), estimator.name())
            : UNCERTAINTY_NOTE;

        return new CostEstimate(
            pricing.id(),
            pricing.provider(),
            CURRENCY_USD,
            inputRate,
            outputRate,
            cacheReadRate,
            savedUsd,
            rawUsd,
            filteredUsd,
            pricing.effectiveDate(),
            pricing.sourceUrl(),
            uncertainty
        );
    }

    private static double round6(double val) {
        return Math.round(val * 1_000_000.0) / 1_000_000.0;
    }
}
