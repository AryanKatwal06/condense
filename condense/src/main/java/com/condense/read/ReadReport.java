package com.condense.read;

import com.condense.analytics.EstimatorInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record ReadReport(
    @JsonProperty("path")
    String path,

    @JsonProperty("language")
    String language,

    @JsonProperty("level")
    String level,

    @JsonProperty("family")
    String family,

    @JsonProperty("contained_by")
    String containedBy,

    @JsonProperty("original_lines")
    int originalLines,

    @JsonProperty("emitted_lines")
    int emittedLines,

    @JsonProperty("raw_bytes")
    int rawBytes,

    @JsonProperty("raw_tokens")
    int rawTokens,

    @JsonProperty("out_tokens")
    int outTokens,

    @JsonProperty("estimator")
    EstimatorInfo estimator,

    @JsonProperty("fallback")
    String fallback,

    @JsonProperty("output")
    String output
) {
    public ReadReport() {
        this(null, null, null, null, null, 0, 0, 0, 0, 0, null, null, null);
    }
}
