package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deep semantic filtering for Vitest test runner output.
 * <p>
 * Preserves failing test indicators, test names, error messages, assertion diffs,
 * and bounded stack traces (capped at 5 lines per failure) while suppressing passing
 * test lines and runner progress noise.
 */
@DeclarativeStage(aliases = {"vitest_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class VitestSummaryStage implements FilterStage {

    public static final VitestSummaryStage INSTANCE = new VitestSummaryStage();

    private static final Pattern FAIL_LINE = Pattern.compile("(?:^|\\s)(?:×|✗|FAIL)(?:\\s|$)", Pattern.UNICODE_CASE);
    private static final Pattern SUMMARY_LINE = Pattern.compile("^Tests\\s+\\d+.*$");
    private static final Pattern STACK_LINE = Pattern.compile("^\\s+at\\s+.*$");

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
                return StageResult.continueWith("✓ all tests passed");
            }
            return StageResult.continueWith(result != null ? result.combined() : raw);
        }

        if (failures.isEmpty() && result != null && result.succeeded()) {
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
        return StageResult.continueWith(sb.toString().stripTrailing());
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
