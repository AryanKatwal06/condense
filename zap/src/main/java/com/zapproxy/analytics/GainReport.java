package com.zapproxy.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zapproxy.core.TrackingRepository.*;

import java.util.List;

/**
 * Serializable snapshot of token savings analytics.
 * All field names use snake_case for JSON output (configured via ObjectMapper).
 *
 * <p>Produced by {@link GainRepository} and emitted by {@link GainCommand}
 * when {@code --format json} is requested.
 */
public record GainReport(

    @JsonProperty("scope")
    String scope,

    @JsonProperty("since_days")
    int sinceDays,

    @JsonProperty("total_commands")
    long totalCommands,

    @JsonProperty("input_tokens")
    long inputTokens,

    @JsonProperty("output_tokens")
    long outputTokens,

    @JsonProperty("tokens_saved")
    long tokensSaved,

    @JsonProperty("savings_pct")
    int savingsPct,

    @JsonProperty("total_exec_ms")
    long totalExecMs,

    @JsonProperty("avg_exec_ms")
    long avgExecMs,

    @JsonProperty("top_commands")
    List<TopCommand> topCommands,

    @JsonProperty("daily")
    List<DailyStat> daily

) {}
