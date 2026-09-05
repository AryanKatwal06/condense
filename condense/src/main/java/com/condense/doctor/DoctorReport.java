package com.condense.doctor;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * Machine-readable diagnosis from {@code condense doctor --format json}.
 */
@RegisterForReflection(targets = {
    DoctorReport.class,
    DoctorReport.HookStatus.class,
    DoctorReport.HookEvent.class
})
public record DoctorReport(
    @JsonProperty("ok") boolean ok,
    @JsonProperty("schema_version") int schemaVersion,
    @JsonProperty("target_schema_version") int targetSchemaVersion,
    @JsonProperty("schema_ahead") boolean schemaAhead,
    @JsonProperty("journal_mode") String journalMode,
    @JsonProperty("degraded") boolean degraded,
    @JsonProperty("empty_tracking_reason") String emptyTrackingReason,
    @JsonProperty("config_dir") String configDir,
    @JsonProperty("data_dir") String dataDir,
    @JsonProperty("database") String database,
    @JsonProperty("command_count") long commandCount,
    @JsonProperty("oldest_command_ts") Long oldestCommandTs,
    @JsonProperty("newest_command_ts") Long newestCommandTs,
    @JsonProperty("outcome_count") long outcomeCount,
    @JsonProperty("outcomes_by_kind") Map<String, Long> outcomesByKind,
    @JsonProperty("trust_store") String trustStore,
    @JsonProperty("trust_entries") int trustEntries,
    @JsonProperty("trust_readable") boolean trustReadable,
    @JsonProperty("project_override") String projectOverride,
    @JsonProperty("global_override") String globalOverride,
    @JsonProperty("hooks") List<HookStatus> hooks,
    @JsonProperty("hook_event_count") long hookEventCount,
    @JsonProperty("hook_events") List<HookEvent> hookEvents,
    @JsonProperty("persistence_write_failures") long persistenceWriteFailures,
    @JsonProperty("persistence_write_last_error") String persistenceWriteLastError,
    @JsonProperty("tee_files") int teeFiles,
    @JsonProperty("tee_oldest_ts") Long teeOldestTs,
    @JsonProperty("tee_old_remaining") int teeOldRemaining,
    @JsonProperty("warnings") List<String> warnings,
    @JsonProperty("next_step") String nextStep
) {
    @RegisterForReflection
    public record HookStatus(
        @JsonProperty("tool") String tool,
        @JsonProperty("installed") boolean installed,
        @JsonProperty("integrity") String integrity,
        @JsonProperty("path") String path
    ) {}

    @RegisterForReflection
    public record HookEvent(
        @JsonProperty("ts") long ts,
        @JsonProperty("tool") String tool,
        @JsonProperty("action") String action,
        @JsonProperty("path") String path,
        @JsonProperty("success") boolean success,
        @JsonProperty("detail") String detail
    ) {}
}
