package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.ir.Document;

import java.util.List;

@DeclarativeStage(aliases = {"git_add_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class GitAddSummaryStage implements FilterStage {
    public static final GitAddSummaryStage INSTANCE = new GitAddSummaryStage();

    private GitAddSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        String output;
        if (raw == null || raw.isBlank()) {
            output = "✓ staged";
        } else {
            long fileCount = raw.lines().filter(l -> !l.isBlank()).count();
            output = "✓ staged " + fileCount + " file(s)";
        }

        if (context != null && context.documentBuilder() != null) {
            context.documentBuilder().git(new Document.GitDocument(
                "",
                true,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                output,
                "add",
                List.of()
            ));
        }

        return StageResult.continueWith(output);
    }
}
