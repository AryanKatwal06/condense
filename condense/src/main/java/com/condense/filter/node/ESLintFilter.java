package com.condense.filter.node;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.Mappers;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.GroupingStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("eslint"),
    @CommandFilter("npx eslint")
})
@ApplicationScoped
public class ESLintFilter extends PipelineBackedFilter {

    public ESLintFilter() {
        super();
    }

    @Inject
    public ESLintFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.builder()
            .addStage(EsLintJsonStage.INSTANCE)
            .addStage(EsLintTextStage.INSTANCE)
            .build();
    }

    static final class EsLintJsonStage implements com.condense.filter.pipeline.FilterStage {
        static final EsLintJsonStage INSTANCE = new EsLintJsonStage();

        @Override
        public StageResult process(String raw, FilterContext ctx) {
            String trimmed = raw.trim();
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                String jsonSummary = tryParseJson(trimmed);
                if (jsonSummary != null) {
                    return StageResult.stopWith(jsonSummary);
                }
            }
            return StageResult.continueWith(raw);
        }

        private static String tryParseJson(String json) {
            try {
                var root = Mappers.JSON.readTree(json);
                if (!root.isArray()) {
                    return null;
                }

                long errors = 0;
                long warnings = 0;
                var grouped = new java.util.LinkedHashMap<String, Integer>();

                for (var file : root) {
                    errors += file.path("errorCount").asLong(0);
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
                return null;
            }
        }
    }

    static final class EsLintTextStage implements com.condense.filter.pipeline.FilterStage {
        static final EsLintTextStage INSTANCE = new EsLintTextStage();
        private static final Pattern RULE_PATTERN =
            Pattern.compile("\\s+\\d+:\\d+\\s+(?:error|warning)\\s+.+?\\s+(\\S+)$");
        private static final GroupingStrategy GROUPING = new GroupingStrategy(RULE_PATTERN, false);

        @Override
        public StageResult process(String raw, FilterContext ctx) {
            List<String> lines = raw.lines().toList();
            long errors = lines.stream().filter(l -> l.contains("  error  ")).count();
            long warnings = lines.stream().filter(l -> l.contains("  warning  ")).count();

            if (errors == 0 && warnings == 0 && (ctx.result() != null && ctx.result().succeeded())) {
                return StageResult.stopWith("✓ no lint issues");
            }

            String formattedGroups = GROUPING.process(raw, ctx).output();
            StringBuilder sb = new StringBuilder("eslint: ").append(errors)
                .append(" error(s), ").append(warnings).append(" warning(s)\n");
            if (!formattedGroups.isBlank()) {
                sb.append(formattedGroups);
            }
            return StageResult.continueWith(sb.toString().stripTrailing());
        }
    }
}
