package com.condense.explain;

import com.condense.analytics.EstimatorInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * Machine-readable report from {@code condense explain --format json}.
 */
@RegisterForReflection(targets = {
    ExplainReport.class,
    ExplainReport.SkippedTier.class,
    ExplainReport.Gate.class,
    ExplainReport.Stage.class,
    ExplainReport.ProvenanceInfo.class,
    ExplainReport.Incident.class
})
public record ExplainReport(
    @JsonProperty("command") String command,
    @JsonProperty("filter") String filter,
    @JsonProperty("tier") String tier,
    @JsonProperty("source") String source,
    @JsonProperty("skipped_tiers") List<SkippedTier> skippedTiers,
    @JsonProperty("gate") Gate gate,
    @JsonProperty("input_lines") int inputLines,
    @JsonProperty("output_lines") int outputLines,
    @JsonProperty("input_tokens") int inputTokens,
    @JsonProperty("output_tokens") int outputTokens,
    @JsonProperty("line_delta") int lineDelta,
    @JsonProperty("token_delta") int tokenDelta,
    @JsonProperty("raw_tokens") int rawTokens,
    @JsonProperty("out_tokens") int outTokens,
    @JsonProperty("was_filtered") boolean wasFiltered,
    @JsonProperty("stages") List<Stage> stages,
    @JsonProperty("provenance") ProvenanceInfo provenance,
    @JsonProperty("filtered_output") String filteredOutput,
    @JsonProperty("estimator") EstimatorInfo estimator,
    @JsonProperty("incidents") List<Incident> incidents,
    @JsonProperty("child_exit_code") int childExitCode,
    @JsonProperty("ok") boolean ok
) {
    public ExplainReport {
        skippedTiers = copy(skippedTiers);
        stages = copy(stages);
        incidents = copy(incidents);
        command = command == null ? "" : command;
        filter = filter == null ? "passthrough" : filter;
        tier = tier == null ? "passthrough" : tier;
        filteredOutput = filteredOutput == null ? "" : filteredOutput;
        estimator = estimator == null ? EstimatorInfo.current() : estimator;
        provenance = provenance == null ? new ProvenanceInfo(false, null) : provenance;
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    @RegisterForReflection
    public record SkippedTier(
        @JsonProperty("tier") String tier,
        @JsonProperty("reason") String reason,
        @JsonProperty("source") String source
    ) {}

    @RegisterForReflection
    public record Gate(
        @JsonProperty("fired") boolean fired,
        @JsonProperty("kind") String kind,
        @JsonProperty("detail") String detail
    ) {}

    @RegisterForReflection
    public record Stage(
        @JsonProperty("id") String id,
        @JsonProperty("status") String status,
        @JsonProperty("input_lines") int inputLines,
        @JsonProperty("output_lines") int outputLines,
        @JsonProperty("input_tokens") int inputTokens,
        @JsonProperty("output_tokens") int outputTokens,
        @JsonProperty("dropped_lines") int droppedLines,
        @JsonProperty("added_lines") int addedLines,
        @JsonProperty("kept_lines") int keptLines,
        @JsonProperty("short_circuit") boolean shortCircuit,
        @JsonProperty("dropped_sample") List<String> droppedSample,
        @JsonProperty("added_sample") List<String> addedSample,
        @JsonProperty("dropped_truncated") boolean droppedTruncated,
        @JsonProperty("added_truncated") boolean addedTruncated,
        @JsonProperty("detail") String detail
    ) {
        public Stage {
            droppedSample = droppedSample == null ? new ArrayList<>() : new ArrayList<>(droppedSample);
            addedSample = addedSample == null ? new ArrayList<>() : new ArrayList<>(addedSample);
            id = id == null ? "" : id;
            status = status == null ? "" : status;
        }
    }

    @RegisterForReflection
    public record ProvenanceInfo(
        @JsonProperty("applied") boolean applied,
        @JsonProperty("stamp") String stamp
    ) {}

    @RegisterForReflection
    public record Incident(
        @JsonProperty("kind") String kind,
        @JsonProperty("stage_name") String stageName,
        @JsonProperty("detail") String detail
    ) {}
}
