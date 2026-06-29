package com.condense.filter.python;

import com.condense.annotation.CommandFilter;
import com.condense.core.*;
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
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        try {
            List<String> output = new ArrayList<>();
            boolean inShortSummary = false;
            String lastLineStr = "";

            try (java.util.stream.Stream<String> stream = result.hasStderr() ? result.stderrLines() : result.stdoutLines()) {
                for (String line : (Iterable<String>) stream::iterator) {
                    if (!line.isBlank()) {
                        lastLineStr = line;
                    }
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
            }

            if (output.isEmpty()) {
                // No failures found
                return FilterResult.of(result, lastLineStr.isBlank() ? "✓ all tests passed" : lastLineStr);
            }

            return FilterResult.of(result, String.join("\n", output));

        } catch (Exception e) {
            log.warnf("PytestFilter error: %s", e.getMessage());
            return FilterResult.passthrough(result);
        }
    }
}