package com.zapproxy.filter.node;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.GroupingStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("eslint"),
    @CommandFilter("npx eslint")
})
@ApplicationScoped
public class ESLintFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(ESLintFilter.class);

    // " 3:14  error  'foo' is not defined  no-undef"
    private static final Pattern RULE_PATTERN =
        Pattern.compile("\\s+\\d+:\\d+\\s+(?:error|warning)\\s+.+?\\s+(\\S+)$");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
            List<String> lines = raw.lines().toList();

            long errors   = lines.stream().filter(l -> l.contains("  error  ")).count();
            long warnings = lines.stream().filter(l -> l.contains("  warning  ")).count();

            if (errors == 0 && warnings == 0 && result.succeeded()) {
                return FilterResult.of(raw, "✓ no lint issues");
            }

            Map<String, Integer> groups = GroupingStrategy.group(lines, RULE_PATTERN, false);

            StringBuilder sb = new StringBuilder();
            sb.append("eslint: ").append(errors).append(" error(s), ")
              .append(warnings).append(" warning(s)\n");
            sb.append(GroupingStrategy.format(groups));

            return FilterResult.of(raw, sb.toString().stripTrailing());
        } catch (Exception e) {
            log.warnf("ESLintFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }
}