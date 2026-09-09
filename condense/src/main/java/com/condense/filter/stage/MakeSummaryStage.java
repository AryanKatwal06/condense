package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;

import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;

import java.util.List;

@DeclarativeStage(aliases = {"make_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class MakeSummaryStage implements FilterStage {
    public static final MakeSummaryStage INSTANCE = new MakeSummaryStage();

    private MakeSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        ExecutionResult result = context.result();
        boolean failed = result != null && !result.succeeded();
        List<String> errors = List.of();
        String output;
        if (failed) {
            errors = raw.lines()
                .filter(l -> l.startsWith("make") || l.contains("Error") || l.contains("error:"))
                .limit(15)
                .toList();
            output = errors.isEmpty() ? raw : String.join("\n", errors);
        } else {
            String lastLine = raw.lines().filter(l -> !l.isBlank()).reduce("", (a, b) -> b);
            output = "✓ make: " + (lastLine.isBlank() ? "done" : lastLine.trim());
        }

        if (context != null && context.documentBuilder() != null) {
            String status = failed ? "FAILED" : "SUCCESS";
            List<String> summaryLines = output != null ? output.lines().toList() : List.of();
            context.documentBuilder().build(new Document.BuildDocument(
                "make",
                status,
                failed ? Math.max(1, errors.size()) : 0,
                0,
                null,
                List.of(),
                summaryLines
            ));
        }

        return StageResult.continueWith(output);
    }
}
