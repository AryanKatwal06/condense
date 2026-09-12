package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deep semantic filtering for Jest test runner output.
 * <p>
 * Preserves failing test suite names, test titles, error messages, assertion diffs,
 * code frames, and bounded stack traces (capped at 5 lines per failure) while
 * suppressing passing test suites and progress noise.
 */
@DeclarativeStage(aliases = {"jest_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class JestSummaryStage implements FilterStage {

    public static final JestSummaryStage INSTANCE = new JestSummaryStage();

    private static final Pattern FAIL_SUITE = Pattern.compile("^\\s*FAIL\\s+(.+)$");
    private static final Pattern PASS_SUITE = Pattern.compile("^\\s*PASS\\s+(.+)$");
    private static final Pattern SUMMARY_LINE = Pattern.compile("^(?:Test Suites:|Tests:|Snapshots:|Time:|Ran all test suites).*$");
    private static final Pattern STACK_LINE = Pattern.compile("^\\s+at\\s+.*$");

    private JestSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        if (raw == null || raw.isBlank()) {
            return StageResult.continueWith(raw != null ? raw : "");
        }

        try {
            return parseJest(raw, context);
        } catch (Exception ignored) {
            return StageResult.continueWith(raw);
        }
    }

    private static StageResult parseJest(String raw, FilterContext context) {
        List<FailedSuite> failedSuites = new ArrayList<>();
        List<String> summaryLines = new ArrayList<>();

        String currentSuite = null;
        List<String> currentDetails = new ArrayList<>();
        int stackTraceCount = 0;

        for (String line : raw.lines().toList()) {
            String trimmed = line.trim();

            Matcher failMatcher = BoundedRegex.matcher(FAIL_SUITE, line);
            if (failMatcher.find()) {
                if (currentSuite != null) {
                    failedSuites.add(new FailedSuite(currentSuite, cleanupDetails(currentDetails)));
                }
                currentSuite = failMatcher.group(1).trim();
                currentDetails = new ArrayList<>();
                stackTraceCount = 0;
                continue;
            }

            Matcher passMatcher = BoundedRegex.matcher(PASS_SUITE, line);
            if (passMatcher.find()) {
                if (currentSuite != null) {
                    failedSuites.add(new FailedSuite(currentSuite, cleanupDetails(currentDetails)));
                    currentSuite = null;
                }
                continue;
            }

            if (BoundedRegex.matcher(SUMMARY_LINE, trimmed).matches()) {
                if (currentSuite != null) {
                    failedSuites.add(new FailedSuite(currentSuite, cleanupDetails(currentDetails)));
                    currentSuite = null;
                }
                if (trimmed.startsWith("Test Suites:") || trimmed.startsWith("Tests:")) {
                    summaryLines.add(trimmed);
                }
                continue;
            }

            if (currentSuite != null) {
                if (trimmed.isEmpty()) {
                    continue;
                }

                if (trimmed.startsWith("at ") || BoundedRegex.matcher(STACK_LINE, line).matches()) {
                    if (stackTraceCount < 5) {
                        currentDetails.add("    " + trimmed);
                        stackTraceCount++;
                    } else if (stackTraceCount == 5) {
                        currentDetails.add("    ... (stack trace omitted) ...");
                        stackTraceCount++;
                    }
                } else if (trimmed.startsWith("●") || trimmed.startsWith("● ")) {
                    currentDetails.add("  " + trimmed);
                    stackTraceCount = 0;
                } else {
                    currentDetails.add("    " + trimmed);
                }
            }
        }

        if (currentSuite != null) {
            failedSuites.add(new FailedSuite(currentSuite, cleanupDetails(currentDetails)));
        }

        ExecutionResult result = context != null ? context.result() : null;
        if (failedSuites.isEmpty() && summaryLines.isEmpty()) {
            if (result != null && result.succeeded()) {
                return StageResult.continueWith("✓ all tests passed");
            }
            return StageResult.continueWith(result != null ? result.combined() : raw);
        }

        if (failedSuites.isEmpty() && result != null && result.succeeded()) {
            return StageResult.continueWith(String.join("\n", summaryLines));
        }

        StringBuilder sb = new StringBuilder();
        if (!failedSuites.isEmpty()) {
            CondenseConfig config = context != null ? context.config() : null;
            int limit = config != null
                ? config.commandConfig("jest").maxFailures(Integer.MAX_VALUE)
                : Integer.MAX_VALUE;
            List<FailedSuite> shown = failedSuites.size() > limit
                ? failedSuites.subList(0, limit) : failedSuites;
            sb.append("jest: ").append(failedSuites.size()).append(" suite(s) failed\n");
            for (FailedSuite suite : shown) {
                sb.append("  FAIL: ").append(suite.suiteName()).append('\n');
                for (String detail : suite.details()) {
                    sb.append(detail).append('\n');
                }
            }
        }

        summaryLines.forEach(l -> sb.append(l).append('\n'));
        return StageResult.continueWith(sb.toString().stripTrailing());
    }

    private static List<String> cleanupDetails(List<String> rawDetails) {
        if (rawDetails == null || rawDetails.isEmpty()) {
            return List.of();
        }
        if (rawDetails.size() <= 25) {
            return rawDetails;
        }
        List<String> bounded = new ArrayList<>(rawDetails.subList(0, 25));
        int omitted = rawDetails.size() - 25;
        bounded.add("    ... (" + omitted + " lines omitted) ...");
        return bounded;
    }

    private record FailedSuite(String suiteName, List<String> details) {}
}
