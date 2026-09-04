package com.condense.analytics;

import com.condense.core.Utf8WeightedTokenEstimator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Metadata describing how token columns in {@link GainReport} were estimated.
 * Always present so callers can show a bound instead of a bare number.
 */
@RegisterForReflection
public record EstimatorInfo(

    @JsonProperty("name")
    String name,

    @JsonProperty("reference")
    String reference,

    @JsonProperty("p95_rel_error")
    double p95RelError
) {
    public static EstimatorInfo current() {
        return new EstimatorInfo(
            Utf8WeightedTokenEstimator.NAME,
            Utf8WeightedTokenEstimator.REFERENCE_TOKENIZER,
            Utf8WeightedTokenEstimator.PUBLISHED_P95_REL_ERROR
        );
    }
}
