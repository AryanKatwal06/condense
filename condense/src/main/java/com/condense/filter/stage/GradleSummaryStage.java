package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;

import java.util.List;
import java.util.regex.Pattern;

public final class GradleSummaryStage implements FilterStage {
    public static final GradleSummaryStage INSTANCE = new GradleSummaryStage();
    private static final Pattern BUILD_SUCCESSFUL = Pattern.compile("BUILD SUCCESSFUL");
    private static final Pattern BUILD_FAILED = Pattern.compile("BUILD FAILED");
    private static final Pattern FAILURE_DETAIL = Pattern.compile("^> ", Pattern.MULTILINE);

    private GradleSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        if (BoundedRegex.find(BUILD_SUCCESSFUL, raw)) {
            String duration = raw.lines()
                .filter(l -> l.contains("BUILD SUCCESSFUL"))
                .findFirst().map(String::trim).orElse("BUILD SUCCESSFUL");
            return StageResult.continueWith("✓ " + duration);
        }
        if (BoundedRegex.find(BUILD_FAILED, raw)) {
            List<String> details = raw.lines()
                .filter(l -> BoundedRegex.find(FAILURE_DETAIL, l) || l.startsWith("FAILURE:"))
                .limit(15)
                .toList();
            return StageResult.continueWith("✗ BUILD FAILED\n" + String.join("\n", details));
        }
        return StageResult.continueWith(context.result() != null ? context.result().combined() : raw);
    }
}
