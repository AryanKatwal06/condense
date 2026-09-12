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
 * Deep semantic filtering for Playwright test runner output.
 * <p>
 * Extracts failing test blocks with test titles, assertion errors, expected/received diffs,
 * and stack traces while stripping verbose browser action call logs, screenshot attachment
 * paths, and voluminous passing test noise. Emits structured {@link Document.TestDocument} IR.
 */
@DeclarativeStage(aliases = {"playwright_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class PlaywrightSummaryStage implements FilterStage {

    public static final PlaywrightSummaryStage INSTANCE = new PlaywrightSummaryStage();

    private static final Pattern FAIL_HEADER = Pattern.compile("^\\s*\\d+\\)\\s+(.+)$");
    private static final Pattern SUMMARY_LINE = Pattern.compile("^\\s*\\d+\\s+(?:failed|passed|flaky|skipped).*$");
    private static final Pattern CALL_LOG = Pattern.compile("^\\s*(?:call\\s+log|Call\\s+log):.*$");
    private static final Pattern ATTACHMENT = Pattern.compile("^\\s*attachment\\s+#\\d+:.*$");
    private static final Pattern DIVIDER = Pattern.compile("^\\s*[─═-]{3,}\\s*$");
    private static final Pattern STACK_LINE = Pattern.compile("^\\s+at\\s+.*$");

    private static final Pattern SUMMARY_FAILED = Pattern.compile("(\\d+)\\s+failed");
    private static final Pattern SUMMARY_PASSED = Pattern.compile("(\\d+)\\s+passed");
    private static final Pattern SUMMARY_SKIPPED = Pattern.compile("(\\d+)\\s+(?:skipped|flaky)");

    private PlaywrightSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        if (raw == null || raw.isBlank()) {
            return StageResult.continueWith(raw != null ? raw : "");
        }

        try {
            return parsePlaywright(raw, context);
        } catch (Exception ignored) {
            return StageResult.continueWith(raw);
        }
    }

    private static StageResult parsePlaywright(String raw, FilterContext context) {
        List<FailedBlock> failures = new ArrayList<>();
        List<String> summaryLines = new ArrayList<>();

        String currentTitle = null;
        List<String> currentDetails = new ArrayList<>();
        boolean inCallLog = false;
        boolean inAttachment = false;
        int stackCount = 0;

        for (String line : raw.lines().toList()) {
            String trimmed = line.trim();

            Matcher failMatcher = BoundedRegex.matcher(FAIL_HEADER, line);
            if (failMatcher.matches()) {
                if (currentTitle != null) {
                    failures.add(new FailedBlock(currentTitle, cleanupDetails(currentDetails)));
                }
                String title = line.replaceAll("[─═-]+$", "").stripTrailing();
                currentTitle = title;
                currentDetails = new ArrayList<>();
                inCallLog = false;
                inAttachment = false;
                stackCount = 0;
                continue;
            }

            if (BoundedRegex.matcher(SUMMARY_LINE, line).matches()) {
                if (currentTitle != null) {
                    failures.add(new FailedBlock(currentTitle, cleanupDetails(currentDetails)));
                    currentTitle = null;
                }
                summaryLines.add(line.stripTrailing());
                continue;
            }

            if (currentTitle != null) {
                if (trimmed.isEmpty()) {
                    inCallLog = false;
                    inAttachment = false;
                    continue;
                }

                if (BoundedRegex.matcher(DIVIDER, trimmed).matches()) {
                    inCallLog = false;
                    inAttachment = false;
                    continue;
                }

                if (BoundedRegex.matcher(CALL_LOG, trimmed).matches()) {
                    inCallLog = true;
                    continue;
                }

                if (inCallLog) {
                    if (trimmed.startsWith("- ") || trimmed.startsWith("waiting for") || line.startsWith("    ")) {
                        continue;
                    }
                    inCallLog = false;
                }

                if (BoundedRegex.matcher(ATTACHMENT, trimmed).matches()) {
                    inAttachment = true;
                    continue;
                }

                if (inAttachment) {
                    if (trimmed.endsWith(".png") || trimmed.endsWith(".zip") || trimmed.startsWith("test-results/")) {
                        continue;
                    }
                    inAttachment = false;
                }

                if (trimmed.startsWith("✓")) {
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

        if (currentTitle != null) {
            failures.add(new FailedBlock(currentTitle, cleanupDetails(currentDetails)));
        }

        ExecutionResult result = context != null ? context.result() : null;
        if (failures.isEmpty() && summaryLines.isEmpty()) {
            if (result != null && result.succeeded()) {
                publishIr(context, failures, summaryLines);
                return StageResult.continueWith("✓ all tests passed");
            }
            return StageResult.continueWith(result != null ? result.combined() : raw);
        }

        if (failures.isEmpty() && result != null && result.succeeded()) {
            publishIr(context, failures, summaryLines);
            return StageResult.continueWith(String.join("\n", summaryLines));
        }

        StringBuilder sb = new StringBuilder();
        for (FailedBlock failure : failures) {
            sb.append(failure.title()).append('\n');
            for (String detail : failure.details()) {
                sb.append(detail).append('\n');
            }
        }
        for (String summary : summaryLines) {
            sb.append(summary).append('\n');
        }

        publishIr(context, failures, summaryLines);
        return StageResult.continueWith(sb.toString().stripTrailing());
    }

    private static void publishIr(FilterContext context, List<FailedBlock> failures, List<String> summaryLines) {
        if (context == null || context.documentBuilder() == null) {
            return;
        }

        int failedCount = failures.size();
        int passedCount = 0;
        int skippedCount = 0;

        for (String line : summaryLines) {
            Matcher mf = SUMMARY_FAILED.matcher(line);
            if (mf.find()) {
                failedCount = Math.max(failedCount, Integer.parseInt(mf.group(1)));
            }
            Matcher mp = SUMMARY_PASSED.matcher(line);
            if (mp.find()) {
                passedCount = Integer.parseInt(mp.group(1));
            }
            Matcher ms = SUMMARY_SKIPPED.matcher(line);
            if (ms.find()) {
                skippedCount += Integer.parseInt(ms.group(1));
            }
        }
        int total = failedCount + passedCount + skippedCount;

        List<Document.TestCase> cases = new ArrayList<>();
        for (FailedBlock fb : failures) {
            String stack = null;
            List<String> stackLines = fb.details().stream()
                .filter(d -> d.trim().startsWith("at "))
                .map(String::trim)
                .toList();
            if (!stackLines.isEmpty()) {
                stack = String.join("\n", stackLines);
            }
            cases.add(new Document.TestCase(
                fb.title(),
                "FAILED",
                String.join("\n", fb.details()),
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
            summaryLines,
            "",
            0,
            total,
            "playwright"
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

    private record FailedBlock(String title, List<String> details) {}
}
