package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.GroupingStrategy;

import java.util.List;
import java.util.regex.Pattern;

public final class EsLintTextStage implements FilterStage {
    public static final EsLintTextStage INSTANCE = new EsLintTextStage();
    private static final Pattern RULE_PATTERN =
        Pattern.compile("\\s+\\d+:\\d+\\s+(?:error|warning)\\s+.+?\\s+(\\S+)$");
    private static final GroupingStrategy GROUPING = new GroupingStrategy(RULE_PATTERN, false);

    private EsLintTextStage() {}

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
