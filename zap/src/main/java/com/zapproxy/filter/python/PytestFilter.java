package com.zapproxy.filter.python;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilter("pytest")
@ApplicationScoped
public class PytestFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(PytestFilter.class);

    private static final Pattern FAILED_LINE  = Pattern.compile("^FAILED\\s+");
    private static final Pattern ERROR_LINE   = Pattern.compile("^ERROR\\s+");
    private static final Pattern SUMMARY_LINE = Pattern.compile("^=+.*=+$");
    private static final Pattern SHORT_TEST_SUMMARY = Pattern.compile("short test summary");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        try {
            String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
            List<String> lines  = raw.lines().toList();
            List<String> output = new ArrayList<>();

            boolean inShortSummary = false;

            for (String line : lines) {
                if (SHORT_TEST_SUMMARY.matcher(line).find()) {
                    inShortSummary = true;
                    continue;
                }
                if (inShortSummary) {
                    if (FAILED_LINE.matcher(line).find() || ERROR_LINE.matcher(line).find()) {
                        output.add(line.trim());
                    } else if (SUMMARY_LINE.matcher(line).find()) {
                        // Final summary line (passed/failed count)
                        output.add(line.trim());
                        inShortSummary = false;
                    }
                } else if (SUMMARY_LINE.matcher(line).find()
                        && (line.contains("passed") || line.contains("failed")
                            || line.contains("error"))) {
                    // Final result line — always emit
                    output.add(line.trim());
                }
            }

            if (output.isEmpty()) {
                // No failures found
                String lastLine = lines.stream().filter(l -> !l.isBlank())
                    .reduce("", (a, b) -> b);
                return FilterResult.of(raw, lastLine.isBlank() ? "✓ all tests passed" : lastLine);
            }

            return FilterResult.of(raw, String.join("\n", output));

        } catch (Exception e) {
            log.warnf("PytestFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result.stdout());
        }
    }
}