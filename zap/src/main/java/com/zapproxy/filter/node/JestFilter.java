package com.zapproxy.filter.node;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilter("jest")
@ApplicationScoped
public class JestFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(JestFilter.class);

    private static final Pattern FAIL_SUITE  = Pattern.compile("^\\s*FAIL\\s+(.+)$");
    private static final Pattern SUMMARY     = Pattern.compile("^Tests:\\s+");
    private static final Pattern TEST_SUITES = Pattern.compile("^Test Suites:");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
            List<String> failedSuites = new ArrayList<>();
            List<String> summaryLines = new ArrayList<>();

            for (String line : raw.lines().toList()) {
                var fm = FAIL_SUITE.matcher(line);
                if (fm.find()) { failedSuites.add("  FAIL: " + fm.group(1).trim()); continue; }
                if (SUMMARY.matcher(line).find() || TEST_SUITES.matcher(line).find()) {
                    summaryLines.add(line.trim());
                }
            }

            if (failedSuites.isEmpty() && result.succeeded()) {
                String summary = summaryLines.isEmpty() ? "✓ all tests passed"
                    : String.join("\n", summaryLines);
                return FilterResult.of(raw, summary);
            }

            StringBuilder sb = new StringBuilder();
            if (!failedSuites.isEmpty()) {
                sb.append("jest: ").append(failedSuites.size()).append(" suite(s) failed\n");
                failedSuites.forEach(l -> sb.append(l).append('\n'));
            }
            summaryLines.forEach(l -> sb.append(l).append('\n'));

            return FilterResult.of(raw, sb.toString().stripTrailing());

        } catch (Exception e) {
            log.warnf("JestFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }
}