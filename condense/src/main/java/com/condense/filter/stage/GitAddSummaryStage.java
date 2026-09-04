package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

public final class GitAddSummaryStage implements FilterStage {
    public static final GitAddSummaryStage INSTANCE = new GitAddSummaryStage();

    private GitAddSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        if (raw.isBlank()) {
            return StageResult.continueWith("✓ staged");
        }
        long fileCount = raw.lines().filter(l -> !l.isBlank()).count();
        return StageResult.continueWith("✓ staged " + fileCount + " file(s)");
    }
}
