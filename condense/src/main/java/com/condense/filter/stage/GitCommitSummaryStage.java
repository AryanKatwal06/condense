package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GitCommitSummaryStage implements FilterStage {
    public static final GitCommitSummaryStage INSTANCE = new GitCommitSummaryStage();
    private static final Pattern COMMIT_LINE =
        Pattern.compile("^\\[([^\\]]+)\\s+([0-9a-f]+)\\]\\s+(.+)$", Pattern.MULTILINE);

    private GitCommitSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        Matcher m = BoundedRegex.matcher(COMMIT_LINE, raw);
        if (m.find()) {
            String branch = m.group(1).trim();
            String hash = m.group(2).substring(0, Math.min(8, m.group(2).length()));
            String message = m.group(3).trim();
            String out = context.ultraCompact()
                ? "[" + branch + "] " + hash + " " + message
                : "✓ committed [" + branch + "] " + hash + " — " + message;
            return StageResult.continueWith(out);
        }
        return StageResult.continueWith("✓ committed");
    }
}
