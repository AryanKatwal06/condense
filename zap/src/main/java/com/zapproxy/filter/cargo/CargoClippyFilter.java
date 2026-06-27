package com.zapproxy.filter.cargo;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.DeduplicationStrategy;
import com.zapproxy.filter.strategy.GroupingStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@CommandFilter("cargo clippy")
@ApplicationScoped
public class CargoClippyFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(CargoClippyFilter.class);

    // "warning: unused variable `x` --> src/main.rs:10:5"
    private static final Pattern WARNING_RULE =
        Pattern.compile("^warning: (.+)$", Pattern.MULTILINE);
    private static final Pattern LINT_NAME =
        Pattern.compile("#\\[warn\\((.+?)\\)\\]");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
            List<String> lines = raw.lines().toList();

            // Extract lint rule names from #[warn(...)] annotations
            Map<String, Integer> groups = GroupingStrategy.group(lines, LINT_NAME, false);

            // Fallback: group by warning message first line
            if (groups.isEmpty()) {
                groups = GroupingStrategy.group(lines, WARNING_RULE, false);
            }

            long warnings = groups.values().stream().mapToLong(Integer::longValue).sum();
            if (warnings == 0 && result.succeeded()) {
                return FilterResult.of(raw, "✓ no clippy warnings");
            }

            StringBuilder sb = new StringBuilder("cargo clippy: ")
                .append(warnings).append(" warning(s)\n");
            sb.append(GroupingStrategy.format(groups));

            return FilterResult.of(raw, sb.toString().stripTrailing());
        } catch (Exception e) {
            log.warnf("CargoClippyFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }
}