package com.condense.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.condense.core.TrackingRepository;
import com.condense.core.TrackingRepository.*;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Serializable snapshot of token savings analytics.
 * All field names use snake_case for JSON output (configured via ObjectMapper).
 *
 * <p>Produced by {@link GainRepository} and emitted by {@link GainCommand}
 * when {@code --format json} is requested.
 */
@RegisterForReflection(targets = {
    GainReport.class,
    EstimatorInfo.class,
    CostEstimate.class,
    ModelPricing.class,
    PricingCatalog.class,
    TrackingRepository.AggregateStats.class,
    TrackingRepository.DailyStat.class,
    TrackingRepository.WeeklyStat.class,
    TrackingRepository.TopCommand.class,
    TrackingRepository.RecentCommand.class
})
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
    List<DailyStat> daily,

    @JsonProperty("estimator")
    EstimatorInfo estimator,

    @JsonProperty("cost")
    CostEstimate cost,

    @JsonProperty("history_status")
    String historyStatus

) {
    public GainReport {
        if (estimator == null) {
            estimator = EstimatorInfo.current();
        }
        if (historyStatus == null) {
            historyStatus = "homogeneous";
        }
    }

    /**
     * Backward-compatible 12-argument constructor defaulting cost to null and history to homogeneous.
     */
    public GainReport(
        String scope,
        int sinceDays,
        long totalCommands,
        long inputTokens,
        long outputTokens,
        long tokensSaved,
        int savingsPct,
        long totalExecMs,
        long avgExecMs,
        List<TopCommand> topCommands,
        List<DailyStat> daily,
        EstimatorInfo estimator
    ) {
        this(
            scope,
            sinceDays,
            totalCommands,
            inputTokens,
            outputTokens,
            tokensSaved,
            savingsPct,
            totalExecMs,
            avgExecMs,
            topCommands,
            daily,
            estimator,
            null,
            "homogeneous"
        );
    }
}
