package com.condense.filter.pipeline.config;

import com.condense.filter.pipeline.FilterPipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Which precedence tier supplied the active pipeline, plus why the others were skipped.
 */
public record PipelineDecision(
    FilterPipeline pipeline,
    String tier,
    String source,
    List<SkippedTier> skipped
) {
    public static final String TIER_PROJECT = "project";
    public static final String TIER_GLOBAL = "global";
    public static final String TIER_BUILTIN = "builtin";
    public static final String TIER_PASSTHROUGH = "passthrough";

    public PipelineDecision {
        skipped = skipped == null ? new ArrayList<>() : new ArrayList<>(skipped);
    }

    public record SkippedTier(String tier, String reason, String source) {}
}
