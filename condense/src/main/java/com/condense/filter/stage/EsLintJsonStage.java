package com.condense.filter.stage;

import com.condense.core.Mappers;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

public final class EsLintJsonStage implements FilterStage {
    public static final EsLintJsonStage INSTANCE = new EsLintJsonStage();

    private EsLintJsonStage() {}

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
