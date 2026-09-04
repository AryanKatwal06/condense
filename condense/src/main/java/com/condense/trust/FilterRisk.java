package com.condense.trust;

import com.condense.filter.pipeline.config.FilterOverrideConfig;
import com.condense.filter.pipeline.config.StageFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Risk report for a project override file, computed from a already-read buffer's parse.
 */
public final class FilterRisk {

    private static final Set<String> CATCH_ALL = Set.of(".", ".*", ".+", "^.*$", "^.+$");

    private FilterRisk() {}

    public record Report(
        List<String> commands,
        List<String> stages,
        Set<Capability> required,
        List<String> catchAllRegexes,
        List<String> reshapeStages,
        List<String> rewriteStages
    ) {
        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("Commands: ").append(commands.isEmpty() ? "(none)" : String.join(", ", commands)).append('\n');
            sb.append("Stages: ").append(stages.isEmpty() ? "(identity)" : String.join(", ", stages)).append('\n');
            sb.append("Required capabilities: ").append(formatCaps(required)).append('\n');
            if (!reshapeStages.isEmpty()) {
                sb.append("Reshape stages: ").append(String.join(", ", reshapeStages)).append('\n');
            }
            if (!rewriteStages.isEmpty()) {
                sb.append("Rewrite stages: ").append(String.join(", ", rewriteStages)).append('\n');
            }
            if (!catchAllRegexes.isEmpty()) {
                sb.append("Catch-all regexes: ").append(String.join(", ", catchAllRegexes)).append('\n');
            }
            return sb.toString();
        }
    }

    public static Report classify(FilterOverrideConfig.FileConfig config) {
        List<String> commands = new ArrayList<>();
        List<String> stages = new ArrayList<>();
        List<String> catchAll = new ArrayList<>();
        List<String> reshape = new ArrayList<>();
        List<String> rewrite = new ArrayList<>();
        Set<Capability> required = new LinkedHashSet<>();
        List<FilterOverrideConfig.StageDef> allStages = new ArrayList<>();

        if (config != null && config.filters() != null) {
            for (var entry : config.filters().entrySet()) {
                commands.add(entry.getKey());
                FilterOverrideConfig.FilterDef def = entry.getValue();
                if (def == null || def.stages() == null) {
                    continue;
                }
                allStages.addAll(def.stages());
                for (FilterOverrideConfig.StageDef stage : def.stages()) {
                    if (stage == null || stage.strategy() == null) {
                        continue;
                    }
                    String alias = StageFactory.normalize(stage.strategy());
                    stages.add(alias);
                    Capability cap = StageFactory.capabilityOf(alias);
                    required.add(cap);
                    if (cap == Capability.RESHAPE) {
                        reshape.add(alias);
                    } else if (cap == Capability.REWRITE) {
                        rewrite.add(alias);
                    }
                    noteCatchAll(stage.pattern(), catchAll);
                    if (stage.transitions() != null) {
                        for (FilterOverrideConfig.TransitionDef t : stage.transitions()) {
                            if (t != null) {
                                noteCatchAll(t.pattern(), catchAll);
                            }
                        }
                    }
                }
            }
        }
        if (required.isEmpty()) {
            required.add(Capability.REDUCE);
        }
        return new Report(
            List.copyOf(commands),
            List.copyOf(stages),
            Set.copyOf(required),
            List.copyOf(catchAll),
            List.copyOf(reshape),
            List.copyOf(rewrite)
        );
    }

    public static Set<Capability> requiredCapabilities(FilterOverrideConfig.FileConfig config) {
        return classify(config).required();
    }

    private static void noteCatchAll(String pattern, List<String> sink) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        String trimmed = pattern.trim();
        if (CATCH_ALL.contains(trimmed) || Pattern.matches("\\^?\\.\\*?\\$?", trimmed)) {
            if (!sink.contains(trimmed)) {
                sink.add(trimmed);
            }
        }
    }

    private static String formatCaps(Set<Capability> caps) {
        List<String> tokens = new ArrayList<>();
        for (Capability cap : caps) {
            tokens.add(cap.token());
        }
        return tokens.isEmpty() ? "(none)" : String.join(", ", tokens);
    }
}
