package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;

import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;
import com.condense.ir.Document;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@DeclarativeStage(aliases = {"git_push_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class GitPushSummaryStage implements FilterStage {
    public static final GitPushSummaryStage INSTANCE = new GitPushSummaryStage();
    public static final Pattern BRANCH_PATTERN = Pattern.compile("\\s+(\\S+)\\s+->\\s+(\\S+)");
    public static final Pattern UP_TO_DATE = Pattern.compile("Everything up-to-date", Pattern.CASE_INSENSITIVE);
    public static final Pattern REJECTED = Pattern.compile("\\[rejected\\]|error:|failed to push");

    private GitPushSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        String output;
        if (BoundedRegex.find(UP_TO_DATE, raw)) {
            output = "✓ up-to-date (nothing pushed)";
        } else {
            Matcher m = BoundedRegex.matcher(BRANCH_PATTERN, raw);
            if (m.find()) {
                output = "✓ pushed → " + m.group(2).trim();
            } else {
                ExecutionResult result = context != null ? context.result() : null;
                if (result != null && result.succeeded()) {
                    output = "✓ pushed";
                } else {
                    output = result != null ? result.combined() : raw;
                }
            }
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
                "push",
                List.of()
            ));
        }

        return StageResult.continueWith(output);
    }
}
