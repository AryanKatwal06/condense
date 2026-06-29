package com.condense.filter.golang;

import com.condense.annotation.CommandFilter;
import com.condense.core.*;
import com.condense.filter.strategy.GroupingStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@CommandFilter("golangci-lint run")
@ApplicationScoped
public class GolangciLintFilter implements FilterStrategy {

    // "src/foo.go:12:3: error message (linter-name)"
    private static final Pattern LINTER_PATTERN =
        Pattern.compile("\\(([a-z][a-z0-9-]+)\\)\\s*$");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        String raw = result.readStdout().isBlank() ? result.readStderr() : result.readStdout();
        List<String> lines = raw.lines().toList();

        Map<String, Integer> groups = GroupingStrategy.group(lines, LINTER_PATTERN, false);
        long total = groups.values().stream().mapToLong(Integer::longValue).sum();

        if (total == 0 && result.succeeded()) return FilterResult.of(result, "✓ no lint issues");

        StringBuilder sb = new StringBuilder("golangci-lint: ").append(total).append(" issue(s)\n");
        sb.append(GroupingStrategy.format(groups));
        return FilterResult.of(result, sb.toString().stripTrailing());
    }
}