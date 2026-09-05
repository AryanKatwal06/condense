package com.condense.propose;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.List;

/**
 * Schema-1 adaptive proposal report. Reviewable only; does not change filtering.
 */
@RegisterForReflection
public record ProposeReport(
    @JsonProperty("schema_version")
    int schemaVersion,

    @JsonProperty("root")
    String root,

    @JsonProperty("discover_recommend")
    List<String> discoverRecommend,

    @JsonProperty("analytics_unavailable")
    boolean analyticsUnavailable,

    @JsonProperty("truncated")
    boolean truncated,

    @JsonProperty("warnings")
    List<String> warnings,

    @JsonProperty("error")
    String error,

    @JsonProperty("proposals")
    List<Proposal> proposals
) {
    public static final int SCHEMA_VERSION = 1;

    public static final String KIND_COVERAGE = "coverage";
    public static final String KIND_SAFETY = "safety";
    public static final String KIND_UNMATCHED = "unmatched";

    public static final String STATUS_READY = "ready";
    public static final String STATUS_BLOCKED_NOT_REPRESENTABLE = "blocked_not_representable";
    public static final String STATUS_BLOCKED_INLINE_TEST = "blocked_inline_test";
    public static final String STATUS_SKIPPED_EXISTING = "skipped_existing";

    public ProposeReport {
        if (discoverRecommend == null) {
            discoverRecommend = Collections.emptyList();
        }
        if (warnings == null) {
            warnings = Collections.emptyList();
        }
        if (proposals == null) {
            proposals = Collections.emptyList();
        }
    }

    public boolean failed() {
        return error != null && !error.isBlank();
    }

    public static ProposeReport failure(String root, String error) {
        return new ProposeReport(
            SCHEMA_VERSION, root, List.of(), false, false, List.of(), error, List.of());
    }

    @RegisterForReflection
    public record Proposal(
        @JsonProperty("id")
        String id,

        @JsonProperty("kind")
        String kind,

        @JsonProperty("status")
        String status,

        @JsonProperty("command")
        String command,

        @JsonProperty("required_capability")
        String requiredCapability,

        @JsonProperty("toml")
        String toml,

        @JsonProperty("evidence")
        Evidence evidence,

        @JsonProperty("before_stages")
        List<String> beforeStages,

        @JsonProperty("after_stages")
        List<String> afterStages,

        @JsonProperty("raw_tokens")
        int rawTokens,

        @JsonProperty("out_tokens")
        int outTokens
    ) {
        public Proposal {
            if (beforeStages == null) {
                beforeStages = Collections.emptyList();
            }
            if (afterStages == null) {
                afterStages = Collections.emptyList();
            }
            if (toml == null) {
                toml = "";
            }
        }
    }

    @RegisterForReflection
    public record Evidence(
        @JsonProperty("definition")
        String definition,

        @JsonProperty("uses")
        Long uses,

        @JsonProperty("incidents")
        Long incidents,

        @JsonProperty("sum_raw")
        Long sumRaw,

        @JsonProperty("reason")
        String reason
    ) {}
}
