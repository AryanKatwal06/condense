package com.condense.filter.stage;

import com.condense.core.CondenseConfig;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;
import com.condense.ir.Document;
import com.condense.ir.TextRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PytestSummaryStage implements FilterStage {
    public static final PytestSummaryStage INSTANCE = new PytestSummaryStage();
    private static final Pattern FAILED_LINE = Pattern.compile("^FAILED\\s+");
    private static final Pattern ERROR_LINE = Pattern.compile("^ERROR\\s+");
    private static final Pattern SUMMARY_LINE = Pattern.compile("^=+.*=+$");
    private static final Pattern SHORT_TEST_SUMMARY = Pattern.compile("short test summary");
    private static final Pattern CASE_NAME = Pattern.compile("^(FAILED|ERROR)\\s+(\\S+)");
    private static final Pattern COUNT_PASSED = Pattern.compile("(\\d+)\\s+passed");
    private static final Pattern COUNT_FAILED = Pattern.compile("(\\d+)\\s+failed");
    private static final Pattern COUNT_ERROR = Pattern.compile("(\\d+)\\s+error");

    private PytestSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        List<String> output = new ArrayList<>();
        boolean inShortSummary = false;
        String lastLineStr = "";

        for (String line : raw.lines().toList()) {
            if (!line.isBlank()) {
                lastLineStr = line;
            }
            if (BoundedRegex.find(SHORT_TEST_SUMMARY, line)) {
                inShortSummary = true;
                continue;
            }
            if (inShortSummary) {
                if (BoundedRegex.find(FAILED_LINE, line) || BoundedRegex.find(ERROR_LINE, line)) {
                    output.add(line.trim());
                } else if (BoundedRegex.find(SUMMARY_LINE, line)) {
                    output.add(line.trim());
                    inShortSummary = false;
                }
            } else if (BoundedRegex.find(SUMMARY_LINE, line)
                && (line.contains("passed") || line.contains("failed") || line.contains("error"))) {
                output.add(line.trim());
            }
        }

        String emptyFallback = lastLineStr.isBlank() ? "✓ all tests passed" : lastLineStr;
        if (output.isEmpty()) {
            Document.TestDocument payload = new Document.TestDocument(
                List.of(), 0, 0, 0, List.of(), emptyFallback);
            publish(context, payload);
            return StageResult.continueWith(TextRenderer.renderTest(payload));
        }

        CondenseConfig config = context.config();
        int limit = config != null
            ? config.commandConfig("pytest").maxFailures(Integer.MAX_VALUE)
            : Integer.MAX_VALUE;
        List<String> shown = output.size() > limit ? output.subList(0, limit) : output;
        if (output.size() > limit) {
            shown = new ArrayList<>(shown);
        }
        Document.TestDocument payload = toPayload(shown, emptyFallback);
        publish(context, payload);
        return StageResult.continueWith(TextRenderer.renderTest(payload));
    }

    private static Document.TestDocument toPayload(List<String> lines, String emptyFallback) {
        List<Document.TestCase> cases = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int errors = 0;
        for (String line : lines) {
            Matcher named = BoundedRegex.matcher(CASE_NAME, line);
            if (named.find()) {
                String status = "FAILED".equals(named.group(1)) ? "failed" : "error";
                cases.add(new Document.TestCase(named.group(2), status, line));
            }
            Matcher p = BoundedRegex.matcher(COUNT_PASSED, line);
            if (p.find()) {
                passed = Integer.parseInt(p.group(1));
            }
            Matcher f = BoundedRegex.matcher(COUNT_FAILED, line);
            if (f.find()) {
                failed = Integer.parseInt(f.group(1));
            }
            Matcher e = BoundedRegex.matcher(COUNT_ERROR, line);
            if (e.find()) {
                errors = Integer.parseInt(e.group(1));
            }
        }
        return new Document.TestDocument(cases, passed, failed, errors, lines, emptyFallback);
    }

    private static void publish(FilterContext context, Document.TestDocument payload) {
        if (context != null && context.documentBuilder() != null) {
            context.documentBuilder().test(payload);
        }
    }
}
