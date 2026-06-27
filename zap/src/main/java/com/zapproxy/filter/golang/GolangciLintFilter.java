package com.zapproxy.filter.golang;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.GroupingStrategy;
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
                              ZapConfig config, int verbose, boolean ultraCompact) {
        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        List<String> lines = raw.lines().toList();

        Map<String, Integer> groups = GroupingStrategy.group(lines, LINTER_PATTERN, false);
        long total = groups.values().stream().mapToLong(Integer::longValue).sum();

        if (total == 0 && result.succeeded()) return FilterResult.of(raw, "✓ no lint issues");

        StringBuilder sb = new StringBuilder("golangci-lint: ").append(total).append(" issue(s)\n");
        sb.append(GroupingStrategy.format(groups));
        return FilterResult.of(raw, sb.toString().stripTrailing());
    }
}