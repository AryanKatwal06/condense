package com.condense.filter.stage;

import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.GroupingStrategy;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class GolangciSummaryStage implements FilterStage {
    public static final GolangciSummaryStage INSTANCE = new GolangciSummaryStage();
    private static final Pattern LINTER_PATTERN =
        Pattern.compile("\\(([a-z][a-z0-9-]+)\\)\\s*$");

    private GolangciSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        List<String> lines = raw.lines().toList();
        Map<String, Integer> groups = GroupingStrategy.group(lines, LINTER_PATTERN, false);
        long total = groups.values().stream().mapToLong(Integer::longValue).sum();
        ExecutionResult result = context.result();
        if (total == 0 && result != null && result.succeeded()) {
            return StageResult.continueWith("✓ no lint issues");
        }
        StringBuilder sb = new StringBuilder("golangci-lint: ").append(total).append(" issue(s)\n");
        sb.append(GroupingStrategy.format(groups));
        return StageResult.continueWith(sb.toString().stripTrailing());
    }
}
