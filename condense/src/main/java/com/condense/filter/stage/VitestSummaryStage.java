package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;
import com.condense.ir.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deep semantic filtering for Vitest test runner output.
 * <p>
 * Preserves failing test indicators, test names, error messages, assertion diffs,
 * and bounded stack traces (capped at 5 lines per failure) while suppressing passing
 * test lines and runner progress noise. Emits structured {@link Document.TestDocument} IR.
 */
@DeclarativeStage(aliases = {"vitest_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class VitestSummaryStage implements FilterStage {

    public static final VitestSummaryStage INSTANCE = new VitestSummaryStage();

    private static final Pattern FAIL_LINE = Pattern.compile("(?:^|\\s)(?:×|✗|FAIL)(?:\\s|$)", Pattern.UNICODE_CASE);
    private static final Pattern SUMMARY_LINE = Pattern.compile("^Tests\\s+\\d+.*$");
    private static final Pattern STACK_LINE = Pattern.compile("^\\s+at\\s+.*$");

    private static final Pattern TESTS_FAILED = Pattern.compile("(\\d+)\\s+failed");
    private static final Pattern TESTS_PASSED = Pattern.compile("(\\d+)\\s+passed");
    private static final Pattern TESTS_SKIPPED = Pattern.compile("(\\d+)\\s+skipped");
    private static final Pattern TESTS_TOTAL = Pattern.compile("\\((\\d+)\\)");

    private VitestSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        if (raw == null || raw.isBlank()) {
            return StageResult.continueWith(raw != null ? raw : "");
        }

        try {
            return parseVitest(raw, context);
        } catch (Exception ignored) {
            return StageResult.continueWith(raw);
        }
    }

    private static StageResult parseVitest(String raw, FilterContext context) {
        List<FailedTest> failures = new ArrayList<>();
        List<String> summary = new ArrayList<>();

        String currentTest = null;
        List<String> currentDetails = new ArrayList<>();
        int stackCount = 0;

        for (String line : raw.lines().toList()) {
            String trimmed = line.trim();

            if (BoundedRegex.find(FAIL_LINE, line) && !line.contains("passed") && !trimmed.isEmpty()) {
                if (currentTest != null) {
                    failures.add(new FailedTest(currentTest, cleanupDetails(currentDetails)));
                }
                currentTest = trimmed;
                currentDetails = new ArrayList<>();
                stackCount = 0;
                continue;
            }

            if (trimmed.startsWith("✓") || trimmed.startsWith("❯")) {
                if (currentTest != null) {
                    failures.add(new FailedTest(currentTest, cleanupDetails(currentDetails)));
                    currentTest = null;
                }
                continue;
            }

            if (BoundedRegex.find(SUMMARY_LINE, trimmed)) {
                if (currentTest != null) {
                    failures.add(new FailedTest(currentTest, cleanupDetails(currentDetails)));
                    currentTest = null;
                }
                summary.add(trimmed);
                continue;
            }

            if (currentTest != null) {
                if (trimmed.isEmpty() || trimmed.startsWith("RUN ")) {
                    continue;
                }

                if (trimmed.startsWith("at ") || BoundedRegex.matcher(STACK_LINE, line).matches()) {
                    if (stackCount < 5) {
                        currentDetails.add("    " + trimmed);
                        stackCount++;
                    } else if (stackCount == 5) {
                        currentDetails.add("    ... (stack trace omitted) ...");
                        stackCount++;
                    }
                } else {
                    currentDetails.add("    " + trimmed);
                }
            }
        }

        if (currentTest != null) {
            failures.add(new FailedTest(currentTest, cleanupDetails(currentDetails)));
        }

        ExecutionResult result = context != null ? context.result() : null;
        if (failures.isEmpty() && summary.isEmpty()) {
            if (result != null && result.succeeded()) {
                publishIr(context, failures, summary, List.of("✓ all tests passed"));
                return StageResult.continueWith("✓ all tests passed");
            }
            return StageResult.continueWith(result != null ? result.combined() : raw);
        }

        if (failures.isEmpty() && result != null && result.succeeded()) {
            publishIr(context, failures, summary, summary);
            return StageResult.continueWith(String.join("\n", summary));
        }

        StringBuilder sb = new StringBuilder();
        if (!failures.isEmpty()) {
            sb.append("vitest: ").append(failures.size()).append(" failure(s)\n");
            for (FailedTest f : failures.stream().limit(20).toList()) {
                sb.append("  ").append(f.testLine()).append('\n');
                for (String detail : f.details()) {
                    sb.append(detail).append('\n');
                }
            }
        }

        summary.forEach(l -> sb.append(l).append('\n'));

        String output = sb.toString().stripTrailing();
        publishIr(context, failures, summary, output.lines().toList());
        return StageResult.continueWith(output);
    }

    private static void publishIr(FilterContext context, List<FailedTest> failures, List<String> summary, List<String> outputLines) {
        if (context == null || context.documentBuilder() == null) {
            return;
        }

        int failedCount = failures.size();
        int passedCount = 0;
        int skippedCount = 0;
        int totalCount = failedCount;

        for (String s : summary) {
            Matcher mFail = BoundedRegex.matcher(TESTS_FAILED, s);
            if (mFail.find()) {
                failedCount = Math.max(failedCount, Integer.parseInt(mFail.group(1)));
            }
            Matcher mPass = BoundedRegex.matcher(TESTS_PASSED, s);
            if (mPass.find()) {
                passedCount = Integer.parseInt(mPass.group(1));
            }
            Matcher mSkip = BoundedRegex.matcher(TESTS_SKIPPED, s);
            if (mSkip.find()) {
                skippedCount = Integer.parseInt(mSkip.group(1));
            }
            Matcher mTot = BoundedRegex.matcher(TESTS_TOTAL, s);
            if (mTot.find()) {
                totalCount = Integer.parseInt(mTot.group(1));
            }
        }
        if (totalCount < failedCount + passedCount + skippedCount) {
            totalCount = failedCount + passedCount + skippedCount;
        }

        List<Document.TestCase> cases = new ArrayList<>();
        for (FailedTest f : failures) {
            String stack = null;
            List<String> stackLines = f.details().stream()
                .filter(d -> d.trim().startsWith("at "))
                .map(String::trim)
                .toList();
            if (!stackLines.isEmpty()) {
                stack = String.join("\n", stackLines);
            }
            cases.add(new Document.TestCase(
                f.testLine(),
                "FAILED",
                String.join("\n", f.details()),
                null,
                null,
                stack
            ));
        }

        context.documentBuilder().test(new Document.TestDocument(
            cases,
            passedCount,
            failedCount,
            skippedCount,
            outputLines,
            "",
            0,
            totalCount,
            "vitest"
        ));
    }

    private static List<String> cleanupDetails(List<String> rawDetails) {
        if (rawDetails == null || rawDetails.isEmpty()) {
            return List.of();
        }
        if (rawDetails.size() <= 20) {
            return rawDetails;
        }
        List<String> bounded = new ArrayList<>(rawDetails.subList(0, 20));
        int omitted = rawDetails.size() - 20;
        bounded.add("    ... (" + omitted + " lines omitted) ...");
        return bounded;
    }

    private record FailedTest(String testLine, List<String> details) {}
}
