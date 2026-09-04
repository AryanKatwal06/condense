package com.condense.filter.stage;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class JestSummaryStage implements FilterStage {
    public static final JestSummaryStage INSTANCE = new JestSummaryStage();
    private static final Pattern FAIL_SUITE = Pattern.compile("^\\s*FAIL\\s+(.+)$");
    private static final Pattern SUMMARY = Pattern.compile("^Tests:\\s+");
    private static final Pattern TEST_SUITES = Pattern.compile("^Test Suites:");

    private JestSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        List<String> failedSuites = new ArrayList<>();
        List<String> summaryLines = new ArrayList<>();
        for (String line : raw.lines().toList()) {
            var fm = BoundedRegex.matcher(FAIL_SUITE, line);
            if (fm.find()) {
                failedSuites.add("  FAIL: " + fm.group(1).trim());
                continue;
            }
            if (BoundedRegex.find(SUMMARY, line) || BoundedRegex.find(TEST_SUITES, line)) {
                summaryLines.add(line.trim());
            }
        }

        ExecutionResult result = context.result();
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
            CondenseConfig config = context.config();
            int limit = config != null
                ? config.commandConfig("jest").maxFailures(Integer.MAX_VALUE)
                : Integer.MAX_VALUE;
            List<String> shown = failedSuites.size() > limit
                ? failedSuites.subList(0, limit) : failedSuites;
            sb.append("jest: ").append(failedSuites.size()).append(" suite(s) failed\n");
            shown.forEach(l -> sb.append(l).append('\n'));
        }
        summaryLines.forEach(l -> sb.append(l).append('\n'));
        return StageResult.continueWith(sb.toString().stripTrailing());
    }
}
