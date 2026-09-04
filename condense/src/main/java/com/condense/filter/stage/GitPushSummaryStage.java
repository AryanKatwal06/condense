package com.condense.filter.stage;

import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GitPushSummaryStage implements FilterStage {
    public static final GitPushSummaryStage INSTANCE = new GitPushSummaryStage();
    public static final Pattern BRANCH_PATTERN = Pattern.compile("\\s+(\\S+)\\s+->\\s+(\\S+)");
    public static final Pattern UP_TO_DATE = Pattern.compile("Everything up-to-date", Pattern.CASE_INSENSITIVE);
    public static final Pattern REJECTED = Pattern.compile("\\[rejected\\]|error:|failed to push");

    private GitPushSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        if (BoundedRegex.find(UP_TO_DATE, raw)) {
            return StageResult.continueWith("✓ up-to-date (nothing pushed)");
        }
        Matcher m = BoundedRegex.matcher(BRANCH_PATTERN, raw);
        if (m.find()) {
            return StageResult.continueWith("✓ pushed → " + m.group(2).trim());
        }
        ExecutionResult result = context.result();
        if (result != null && result.succeeded()) {
            return StageResult.continueWith("✓ pushed");
        }
        return StageResult.continueWith(result != null ? result.combined() : raw);
    }
}
