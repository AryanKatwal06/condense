package com.zapproxy.filter.python;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.GroupingStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("ruff check"),
    @CommandFilter("ruff")
})
@ApplicationScoped
public class RuffFilter implements FilterStrategy {

    // "src/foo.py:12:3: E501 Line too long"
    private static final Pattern RULE_PATTERN =
        Pattern.compile(":\\s+([A-Z]\\d+)\\s");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        String raw = result.readStdout().isBlank() ? result.readStderr() : result.readStdout();
        List<String> lines = raw.lines().toList();

        Map<String, Integer> groups = GroupingStrategy.group(lines, RULE_PATTERN, false);
        long total = groups.values().stream().mapToLong(Integer::longValue).sum();

        if (total == 0 && result.succeeded()) return FilterResult.of(result, "✓ no lint issues");

        StringBuilder sb = new StringBuilder("ruff: ").append(total).append(" issue(s)\n");
        sb.append(GroupingStrategy.format(groups));
        return FilterResult.of(result, sb.toString().stripTrailing());
    }
}