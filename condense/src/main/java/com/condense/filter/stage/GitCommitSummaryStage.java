package com.condense.filter.stage;

import com.condense.annotation.DeclarativeStage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;
import com.condense.ir.Document;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@DeclarativeStage(aliases = {"git_commit_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class GitCommitSummaryStage implements FilterStage {
    public static final GitCommitSummaryStage INSTANCE = new GitCommitSummaryStage();
    private static final Pattern COMMIT_LINE =
        Pattern.compile("^\\[([^\\]]+)\\s+([0-9a-f]+)\\]\\s+(.+)$", Pattern.MULTILINE);

    private GitCommitSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        Matcher m = BoundedRegex.matcher(COMMIT_LINE, raw);
        String branch = "";
        String out;
        if (m.find()) {
            branch = m.group(1).trim();
            String hash = m.group(2).substring(0, Math.min(8, m.group(2).length()));
            String message = m.group(3).trim();
            out = (context != null && context.ultraCompact())
                ? "[" + branch + "] " + hash + " " + message
                : "✓ committed [" + branch + "] " + hash + " — " + message;
        } else {
            out = "✓ committed";
        }

        if (context != null && context.documentBuilder() != null) {
            context.documentBuilder().git(new Document.GitDocument(
                branch,
                true,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                out,
                "commit",
                List.of()
            ));
        }

        return StageResult.continueWith(out);
    }
}
