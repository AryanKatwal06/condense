package com.condense.filter.node;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.*;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.GroupingStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("eslint"),
    @CommandFilter("npx eslint")
})
@ApplicationScoped
public class ESLintFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(ESLintFilter.class);

    // " 3:14  error  'foo' is not defined  no-undef"
    private static final Pattern RULE_PATTERN =
        Pattern.compile("\\s+\\d+:\\d+\\s+(?:error|warning)\\s+.+?\\s+(\\S+)$");

    private final FilterPipeline pipeline;

    public ESLintFilter() {
        GroupingStrategy groupingStage = new GroupingStrategy(RULE_PATTERN, false);

        this.pipeline = FilterPipeline.builder()
            // Stage 1: JSON format short-circuit
            .addStage((raw, ctx) -> {
                String trimmed = raw.trim();
                if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                    String jsonSummary = tryParseJson(trimmed);
                    if (jsonSummary != null) {
                        return StageResult.stopWith(jsonSummary);
                    }
                }
                return StageResult.continueWith(raw);
            })
            // Stage 2: Text format parsing and rule grouping
            .addStage((raw, ctx) -> {
                List<String> lines = raw.lines().toList();
                long errors   = lines.stream().filter(l -> l.contains("  error  ")).count();
                long warnings = lines.stream().filter(l -> l.contains("  warning  ")).count();

                if (errors == 0 && warnings == 0 && (ctx.result() != null && ctx.result().succeeded())) {
                    return StageResult.stopWith("✓ no lint issues");
                }

                String formattedGroups = groupingStage.process(raw, ctx).output();
                StringBuilder sb = new StringBuilder("eslint: ").append(errors)
                    .append(" error(s), ").append(warnings).append(" warning(s)\n");
                if (!formattedGroups.isBlank()) {
                    sb.append(formattedGroups);
                }
                return StageResult.continueWith(sb.toString().stripTrailing());
            })
            .build();
    }

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.readStdout().isBlank() ? result.readStderr()
                                                        : result.readStdout();
            FilterContext context = FilterContext.of(command, result, config, verbose, ultraCompact);
            String output = pipeline.execute(raw, context);
            return FilterResult.of(result, output);
        } catch (Exception e) {
            log.warnf("ESLintFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result);
        }
    }

    private static String tryParseJson(String json) {
        try {
            var root = Mappers.JSON.readTree(json);
            if (!root.isArray()) return null;

            long errors = 0, warnings = 0;
            var grouped = new java.util.LinkedHashMap<String, Integer>();

            for (var file : root) {
                errors   += file.path("errorCount").asLong(0);
                warnings += file.path("warningCount").asLong(0);
                for (var msg : file.path("messages")) {
                    String ruleId = msg.path("ruleId").asText("unknown");
                    grouped.merge(ruleId, 1, Integer::sum);
                }
            }

            if (errors == 0 && warnings == 0) {
                return "✓ no lint issues";
            }

            StringBuilder sb = new StringBuilder("eslint: ")
                .append(errors).append(" error(s), ")
                .append(warnings).append(" warning(s)\n");
            grouped.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> sb.append("  ").append(e.getKey())
                                .append(": ").append(e.getValue()).append('\n'));
            return sb.toString().stripTrailing();

        } catch (Exception e) {
            return null; // Not valid JSON or wrong shape — caller will try text fallback
        }
    }
}