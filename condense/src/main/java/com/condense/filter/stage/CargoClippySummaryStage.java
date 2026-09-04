package com.condense.filter.stage;

import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.GroupingStrategy;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class CargoClippySummaryStage implements FilterStage {
    public static final CargoClippySummaryStage INSTANCE = new CargoClippySummaryStage();
    private static final Pattern WARNING_RULE =
        Pattern.compile("^warning: (.+)$", Pattern.MULTILINE);
    private static final Pattern LINT_NAME =
        Pattern.compile("#\\[warn\\((.+?)\\)\\]");

    private CargoClippySummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        List<String> lines = raw.lines().toList();
        Map<String, Integer> groups = GroupingStrategy.group(lines, LINT_NAME, false);
        if (groups.isEmpty()) {
            groups = GroupingStrategy.group(lines, WARNING_RULE, false);
        }
        long warnings = groups.values().stream().mapToLong(Integer::longValue).sum();
        ExecutionResult result = context.result();
        if (warnings == 0 && result != null && result.succeeded()) {
            return StageResult.continueWith("✓ no clippy warnings");
        }
        StringBuilder sb = new StringBuilder("cargo clippy: ")
            .append(warnings).append(" warning(s)\n");
        sb.append(GroupingStrategy.format(groups));
        return StageResult.continueWith(sb.toString().stripTrailing());
    }
}
