package com.condense.filter.stage;

import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.GroupingStrategy;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class RuffSummaryStage implements FilterStage {
    public static final RuffSummaryStage INSTANCE = new RuffSummaryStage();
    private static final Pattern RULE_PATTERN = Pattern.compile(":\\s+([A-Z]\\d+)\\s");

    private RuffSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        List<String> lines = raw.lines().toList();
        Map<String, Integer> groups = GroupingStrategy.group(lines, RULE_PATTERN, false);
        long total = groups.values().stream().mapToLong(Integer::longValue).sum();
        ExecutionResult result = context.result();
        if (total == 0 && result != null && result.succeeded()) {
            return StageResult.continueWith("✓ no lint issues");
        }
        StringBuilder sb = new StringBuilder("ruff: ").append(total).append(" issue(s)\n");
        sb.append(GroupingStrategy.format(groups));
        return StageResult.continueWith(sb.toString().stripTrailing());
    }
}
