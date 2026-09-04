package com.condense.filter.stage;

import com.condense.core.CondenseConfig;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class PytestSummaryStage implements FilterStage {
    public static final PytestSummaryStage INSTANCE = new PytestSummaryStage();
    private static final Pattern FAILED_LINE = Pattern.compile("^FAILED\\s+");
    private static final Pattern ERROR_LINE = Pattern.compile("^ERROR\\s+");
    private static final Pattern SUMMARY_LINE = Pattern.compile("^=+.*=+$");
    private static final Pattern SHORT_TEST_SUMMARY = Pattern.compile("short test summary");

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

        if (output.isEmpty()) {
            return StageResult.continueWith(lastLineStr.isBlank() ? "✓ all tests passed" : lastLineStr);
        }

        CondenseConfig config = context.config();
        int limit = config != null
            ? config.commandConfig("pytest").maxFailures(Integer.MAX_VALUE)
            : Integer.MAX_VALUE;
        List<String> shown = output.size() > limit ? output.subList(0, limit) : output;
        if (output.size() > limit) {
            shown = new ArrayList<>(shown);
        }
        return StageResult.continueWith(String.join("\n", shown));
    }
}
