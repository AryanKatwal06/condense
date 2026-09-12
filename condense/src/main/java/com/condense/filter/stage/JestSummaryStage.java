package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.AnsiStripStrategy;
import com.condense.filter.strategy.BoundedRegex;
import com.condense.ir.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deep semantic filtering for Jest test runner output.
 * <p>
 * Preserves failing test suite names, individual test titles, error messages, assertion diffs,
 * code frames, and bounded stack traces while suppressing passing test suites and progress noise.
 * Emits typed {@link Document.TestDocument} when {@link FilterContext#documentBuilder()} is available.
 */
@DeclarativeStage(aliases = {"jest_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class JestSummaryStage implements FilterStage {

    public static final JestSummaryStage INSTANCE = new JestSummaryStage();

    private static final Pattern TOP_FAIL_SUITE = Pattern.compile("^FAIL\\s+(.+)$");
    private static final Pattern TOP_PASS_SUITE = Pattern.compile("^PASS\\s+(.+)$");
    private static final Pattern SUMMARY_LINE = Pattern.compile("^(?:Test Suites:|Tests:|Snapshots:|Time:|Ran all test suites).*$");
    private static final Pattern STACK_LINE = Pattern.compile("^\\s+at\\s+.*$");
    private static final Pattern TESTS_COUNT = Pattern.compile("(\\d+)\\s+failed");
    private static final Pattern PASSED_COUNT = Pattern.compile("(\\d+)\\s+passed");
    private static final Pattern TOTAL_COUNT = Pattern.compile("(\\d+)\\s+total");

    private static final int MAX_DETAIL_LINES_PER_FAILURE = 15;
    private static final int MAX_STACK_LINES_PER_FAILURE = 5;

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
        // Defensive ANSI stripping ensures unstripped raw terminal output parses cleanly
        String clean = AnsiStripStrategy.strip(raw);

        List<FailedSuite> failedSuites = new ArrayList<>();
        List<String> summaryLines = new ArrayList<>();

        String currentSuiteName = null;
        List<FailureBuilder> currentFailures = new ArrayList<>();
        FailureBuilder activeFailure = null;

        for (String line : clean.lines().toList()) {
            String trimmed = line.trim();

            // Structural check: Top-level unindented suite boundaries only.
            // Indented lines (e.g. inside console.log or test output) can never start/close a suite.
            boolean isTopLevel = !line.startsWith(" ") && !line.startsWith("\t");

            if (isTopLevel) {
                Matcher failMatcher = BoundedRegex.matcher(TOP_FAIL_SUITE, line);
                if (failMatcher.find()) {
                    if (currentSuiteName != null) {
                        failedSuites.add(buildSuite(currentSuiteName, currentFailures));
                    }
                    currentSuiteName = failMatcher.group(1).trim();
                    currentFailures = new ArrayList<>();
                    activeFailure = null;
                    continue;
                }

                Matcher passMatcher = BoundedRegex.matcher(TOP_PASS_SUITE, line);
                if (passMatcher.find()) {
                    if (currentSuiteName != null) {
                        failedSuites.add(buildSuite(currentSuiteName, currentFailures));
                        currentSuiteName = null;
                        currentFailures = new ArrayList<>();
                        activeFailure = null;
                    }
                    continue;
                }
            }

            if (BoundedRegex.matcher(SUMMARY_LINE, trimmed).matches()) {
                if (currentSuiteName != null) {
                    failedSuites.add(buildSuite(currentSuiteName, currentFailures));
                    currentSuiteName = null;
                    currentFailures = new ArrayList<>();
                    activeFailure = null;
                }
                if (trimmed.startsWith("Test Suites:") || trimmed.startsWith("Tests:")) {
                    summaryLines.add(trimmed);
                }
                continue;
            }

            if (currentSuiteName != null) {
                if (trimmed.isEmpty()) {
                    continue;
                }

                if (trimmed.startsWith("●") || trimmed.startsWith("● ")) {
                    // New individual test failure within this suite
                    String title = trimmed.replaceFirst("^●\\s*", "");
                    activeFailure = new FailureBuilder(title);
                    currentFailures.add(activeFailure);
                } else if (activeFailure != null) {
                    // Lines under an active test failure
                    if (trimmed.startsWith("at ") || BoundedRegex.matcher(STACK_LINE, line).matches()) {
                        activeFailure.addStack("    " + trimmed);
                    } else {
                        activeFailure.addDetail("    " + trimmed);
                    }
                }
            }
        }

        if (currentSuiteName != null) {
            failedSuites.add(buildSuite(currentSuiteName, currentFailures));
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
                for (SingleFailure failure : suite.failures()) {
                    sb.append("  ● ").append(failure.title()).append('\n');
                    for (String detail : failure.details()) {
                        sb.append(detail).append('\n');
                    }
                }
            }
        }

        summaryLines.forEach(l -> sb.append(l).append('\n'));

        // Publish structured TestDocument IR
        publishIr(context, failedSuites, summaryLines);

        return StageResult.continueWith(sb.toString().stripTrailing());
    }

    private static FailedSuite buildSuite(String suiteName, List<FailureBuilder> builders) {
        List<SingleFailure> failures = new ArrayList<>();
        for (FailureBuilder b : builders) {
            failures.add(b.build());
        }
        if (failures.isEmpty()) {
            failures.add(new SingleFailure("(suite error)", List.of(), null));
        }
        return new FailedSuite(suiteName, failures);
    }

    private static void publishIr(FilterContext context, List<FailedSuite> failedSuites, List<String> summaryLines) {
        if (context == null || context.documentBuilder() == null) {
            return;
        }
        List<Document.TestCase> cases = new ArrayList<>();
        int failedCount = 0;
        for (FailedSuite suite : failedSuites) {
            for (SingleFailure f : suite.failures()) {
                failedCount++;
                String detail = String.join("\n", f.details());
                cases.add(new Document.TestCase(
                    f.title(),
                    "FAILED",
                    detail,
                    suite.suiteName(),
                    null,
                    f.stackTrace()
                ));
            }
        }

        int passedCount = 0;
        int totalCount = failedCount;
        for (String line : summaryLines) {
            Matcher fm = TESTS_COUNT.matcher(line);
            if (fm.find()) {
                failedCount = Math.max(failedCount, Integer.parseInt(fm.group(1)));
            }
            Matcher pm = PASSED_COUNT.matcher(line);
            if (pm.find()) {
                passedCount = Integer.parseInt(pm.group(1));
            }
            Matcher tm = TOTAL_COUNT.matcher(line);
            if (tm.find()) {
                totalCount = Integer.parseInt(tm.group(1));
            }
        }

        context.documentBuilder().test(new Document.TestDocument(
            cases,
            passedCount,
            failedCount,
            0,
            summaryLines,
            "",
            0,
            totalCount,
            "jest"
        ));
    }

    private static final class FailureBuilder {
        private final String title;
        private final List<String> details = new ArrayList<>();
        private final List<String> stackLines = new ArrayList<>();
        private int detailLinesCount = 0;
        private int stackLinesCount = 0;

        FailureBuilder(String title) {
            this.title = title;
        }

        void addDetail(String line) {
            if (detailLinesCount < MAX_DETAIL_LINES_PER_FAILURE) {
                details.add(line);
                detailLinesCount++;
            } else if (detailLinesCount == MAX_DETAIL_LINES_PER_FAILURE) {
                details.add("    ... (detail lines omitted) ...");
                detailLinesCount++;
            }
        }

        void addStack(String line) {
            if (stackLinesCount < MAX_STACK_LINES_PER_FAILURE) {
                details.add(line);
                stackLines.add(line.trim());
                stackLinesCount++;
            } else if (stackLinesCount == MAX_STACK_LINES_PER_FAILURE) {
                details.add("    ... (stack trace omitted) ...");
                stackLinesCount++;
            }
        }

        SingleFailure build() {
            String stack = stackLines.isEmpty() ? null : String.join("\n", stackLines);
            return new SingleFailure(title, details, stack);
        }
    }

    private record SingleFailure(String title, List<String> details, String stackTrace) {}

    private record FailedSuite(String suiteName, List<SingleFailure> failures) {}
}
