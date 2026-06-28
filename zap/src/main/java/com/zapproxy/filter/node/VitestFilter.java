package com.zapproxy.filter.node;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilter("vitest")
@ApplicationScoped
public class VitestFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(VitestFilter.class);

    private static final Pattern FAIL_LINE    = Pattern.compile("×|✗|FAIL", Pattern.UNICODE_CASE);
    private static final Pattern SUMMARY_LINE = Pattern.compile("Tests\\s+\\d+");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            List<String> failures = new ArrayList<>();
            List<String> summary  = new ArrayList<>();

            try (java.util.stream.Stream<String> stream = result.hasStderr() ? result.stderrLines() : result.stdoutLines()) {
                for (String line : (Iterable<String>) stream::iterator) {
                    if (FAIL_LINE.matcher(line).find()
                            && !line.contains("passed") && !line.isBlank()) {
                        failures.add("  " + line.trim());
                    } else if (SUMMARY_LINE.matcher(line).find()) {
                        summary.add(line.trim());
                    }
                }
            }

            if (failures.isEmpty() && result.succeeded()) {
                return FilterResult.of(result, summary.isEmpty() ? "✓ all tests passed" : String.join("\n", summary));
            }

            StringBuilder sb = new StringBuilder();
            if (!failures.isEmpty()) {
                sb.append("vitest: ").append(failures.size()).append(" failure(s)\n");
                failures.stream().limit(20).forEach(l -> sb.append(l).append('\n'));
            }
            summary.forEach(l -> sb.append(l).append('\n'));

            return FilterResult.of(result, sb.toString().stripTrailing());
        } catch (Exception e) {
            log.warnf("VitestFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result);
        }
    }
}