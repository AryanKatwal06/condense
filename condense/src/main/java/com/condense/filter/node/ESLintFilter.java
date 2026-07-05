package com.condense.filter.node;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.*;
import com.condense.filter.strategy.GroupingStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
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

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        try {
            // Try JSON output first (from eslint --format json)
            String raw = result.readStdout().isBlank() ? result.readStderr()
                                                        : result.readStdout();
            String trimmed = raw.trim();
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                FilterResult jsonResult = tryParseJson(result, trimmed);
                if (jsonResult != null) return jsonResult;
            }

            // Fallback: parse the human-readable text format
            return parseText(result, raw, verbose, ultraCompact);
        } catch (Exception e) {
            log.warnf("ESLintFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result);
        }
    }

    private FilterResult tryParseJson(ExecutionResult result, String json) {
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
                return FilterResult.of(result, "✓ no lint issues");
            }

            StringBuilder sb = new StringBuilder("eslint: ")
                .append(errors).append(" error(s), ")
                .append(warnings).append(" warning(s)\n");
            grouped.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> sb.append("  ").append(e.getKey())
                                .append(": ").append(e.getValue()).append('\n'));
            return FilterResult.of(result, sb.toString().stripTrailing());

        } catch (Exception e) {
            return null; // Not valid JSON or wrong shape — caller will try text fallback
        }
    }

    private FilterResult parseText(ExecutionResult result, String raw,
                                   int verbose, boolean ultraCompact) {
        List<String> lines = raw.lines().toList();
        long errors   = lines.stream().filter(l -> l.contains("  error  ")).count();
        long warnings = lines.stream().filter(l -> l.contains("  warning  ")).count();

        if (errors == 0 && warnings == 0 && result.succeeded()) {
            return FilterResult.of(result, "✓ no lint issues");
        }

        Map<String, Integer> groups = GroupingStrategy.group(lines, RULE_PATTERN, false);
        StringBuilder sb = new StringBuilder("eslint: ").append(errors)
            .append(" error(s), ").append(warnings).append(" warning(s)\n");
        sb.append(GroupingStrategy.format(groups));
        return FilterResult.of(result, sb.toString().stripTrailing());
    }
}