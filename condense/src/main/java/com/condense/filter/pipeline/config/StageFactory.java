package com.condense.filter.pipeline.config;

import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.NamedStage;
import com.condense.trust.Capability;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Facade over {@link GeneratedStageRegistry}. Adding a stage means annotating
 * the implementation; this class is not edited for new aliases.
 */
public final class StageFactory {

    public static final long REGEX_TIMEOUT_MS = 200L;
    public static final int MAX_PATTERN_LENGTH = 500;
    public static final int MAX_TRANSITIONS_COUNT = 50;

    public static final Set<String> ALLOWED_ALIASES = GeneratedStageRegistry.ALLOWED_ALIASES;

    static final String CANONICAL_LIST =
        "ansi_strip, tree_compression, json_structure, deduplication, grouping, state_machine, "
            + "tail_lines, head_tail, aggregate_by_key, regex_capture, git_status, json_lines, docker_ps, "
            + "and the named command-specific summaries";

    private StageFactory() {}

    public static String normalize(String strategy) {
        return strategy == null ? "" : strategy.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Maps hyphenated and short aliases to the underscore form used by explain.
     */
    public static String canonicalAlias(String strategy) {
        return GeneratedStageRegistry.canonicalAlias(normalize(strategy));
    }

    public static boolean isAllowed(String strategy) {
        return ALLOWED_ALIASES.contains(normalize(strategy));
    }

    /**
     * Capability class for a strategy alias. Unknown aliases are treated as reshape
     * (they will fail validation before a trusted file can apply them).
     */
    public static Capability capabilityOf(String strategy) {
        return GeneratedStageRegistry.capabilityOf(normalize(strategy));
    }

    public static Set<Capability> requiredCapabilities(List<FilterOverrideConfig.StageDef> stages) {
        if (stages == null || stages.isEmpty()) {
            return Set.of(Capability.REDUCE);
        }
        Set<Capability> caps = new LinkedHashSet<>();
        for (FilterOverrideConfig.StageDef stage : stages) {
            if (stage != null && stage.strategy() != null && !stage.strategy().isBlank()) {
                caps.add(capabilityOf(stage.strategy()));
            }
        }
        return caps.isEmpty() ? Set.of(Capability.REDUCE) : Set.copyOf(caps);
    }

    public static FilterPipeline buildPipeline(List<FilterOverrideConfig.StageDef> stages) {
        FilterPipeline.Builder builder = FilterPipeline.builder();
        if (stages == null || stages.isEmpty()) {
            return builder.build();
        }
        for (FilterOverrideConfig.StageDef stageDef : stages) {
            FilterStage stage = instantiate(stageDef);
            if (stage != null) {
                builder.addStage(stage);
            }
        }
        return builder.build();
    }

    public static FilterStage instantiate(FilterOverrideConfig.StageDef stageDef) {
        if (stageDef == null) {
            return null;
        }
        FilterStage stage = GeneratedStageRegistry.instantiateRaw(stageDef);
        if (stage == null) {
            return null;
        }
        return NamedStage.wrap(canonicalAlias(stageDef.strategy()), stage);
    }

    public static void validate(String location, FilterOverrideConfig.StageDef stage, List<String> errors) {
        if (stage == null) {
            errors.add(location + ": Stage configuration is null");
            return;
        }
        String strategy = stage.strategy();
        if (strategy == null || strategy.isBlank()) {
            errors.add(location + ": Missing required 'strategy' field");
            return;
        }
        String normalized = normalize(strategy);
        if (!ALLOWED_ALIASES.contains(normalized)) {
            errors.add(location + ": Unknown strategy: '" + strategy + "'. Allowed strategies: " + CANONICAL_LIST);
            return;
        }
        GeneratedStageRegistry.validate(location, stage, errors);
    }

    public static Set<String> canonicalAliases() {
        Set<String> canonical = new LinkedHashSet<>();
        for (String alias : ALLOWED_ALIASES) {
            if (!alias.contains("-") && !Set.of("ansi", "tree", "json", "dedup", "group").contains(alias)) {
                canonical.add(alias);
            }
        }
        return canonical;
    }
}
