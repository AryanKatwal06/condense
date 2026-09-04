package com.condense.filter.stage;

import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

import java.util.List;

public final class MakeSummaryStage implements FilterStage {
    public static final MakeSummaryStage INSTANCE = new MakeSummaryStage();

    private MakeSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        ExecutionResult result = context.result();
        if (result != null && !result.succeeded()) {
            List<String> errors = raw.lines()
                .filter(l -> l.startsWith("make") || l.contains("Error") || l.contains("error:"))
                .limit(15)
                .toList();
            return StageResult.continueWith(errors.isEmpty() ? raw : String.join("\n", errors));
        }
        String lastLine = raw.lines().filter(l -> !l.isBlank()).reduce("", (a, b) -> b);
        return StageResult.continueWith("✓ make: " + (lastLine.isBlank() ? "done" : lastLine.trim()));
    }
}
