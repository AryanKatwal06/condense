package com.condense.filter.node;

import com.condense.annotation.CommandFilter;
import com.condense.core.*;
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
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        try {
            List<String> failedSuites = new ArrayList<>();
            List<String> summaryLines = new ArrayList<>();

            try (java.util.stream.Stream<String> stream = result.hasStderr() ? result.stderrLines() : result.stdoutLines()) {
                for (String line : (Iterable<String>) stream::iterator) {
                    var fm = FAIL_SUITE.matcher(line);
                    if (fm.find()) { failedSuites.add("  FAIL: " + fm.group(1).trim()); continue; }
                    if (SUMMARY.matcher(line).find() || TEST_SUITES.matcher(line).find()) {
                        summaryLines.add(line.trim());
                    }
                }
            }

            if (failedSuites.isEmpty() && summaryLines.isEmpty()) {
                if (result.succeeded()) return FilterResult.of(result, "✓ all tests passed");
                return FilterResult.passthrough(result);
            }

            if (failedSuites.isEmpty() && result.succeeded()) {
                return FilterResult.of(result, String.join("\n", summaryLines));
            }

            StringBuilder sb = new StringBuilder();
            if (!failedSuites.isEmpty()) {
                CondenseConfig.CommandConfig cc = config.commandConfig("jest");
                int limit = cc.maxFailures(Integer.MAX_VALUE);
                List<String> shown = failedSuites.size() > limit ? failedSuites.subList(0, limit) : failedSuites;
                
                sb.append("jest: ").append(failedSuites.size()).append(" suite(s) failed\n");
                shown.forEach(l -> sb.append(l).append('\n'));
            }
            summaryLines.forEach(l -> sb.append(l).append('\n'));

            return FilterResult.of(result, sb.toString().stripTrailing());

        } catch (Exception e) {
            log.warnf("JestFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result);
        }
    }
}