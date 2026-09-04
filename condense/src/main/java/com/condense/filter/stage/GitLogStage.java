package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class GitLogStage implements FilterStage {
    public static final GitLogStage INSTANCE = new GitLogStage();
    private static final Pattern COMMIT_PATTERN =
        Pattern.compile("^commit ([0-9a-f]{8})[0-9a-f]*");

    private GitLogStage() {}

    @Override
    public StageResult process(String stdout, FilterContext context) {
        List<String> commits = new ArrayList<>();
        String currentHash = null;
        for (String line : stdout.lines().toList()) {
            var m = BoundedRegex.matcher(COMMIT_PATTERN, line);
            if (m.find()) {
                currentHash = m.group(1);
            } else if (currentHash != null && !line.isBlank()
                && !line.startsWith("Author:")
                && !line.startsWith("Date:")
                && !line.startsWith("Merge:")) {
                commits.add(currentHash + " " + line.trim());
                currentHash = null;
            }
        }
        if (commits.isEmpty()) {
            return StageResult.continueWith("(no commits)");
        }
        int limit = context.verbose() >= 2 ? commits.size() : Math.min(10, commits.size());
        String out = String.join("\n", commits.subList(0, limit));
        if (!context.ultraCompact() && commits.size() > limit) {
            out += "\n(+" + (commits.size() - limit) + " more)";
        }
        return StageResult.continueWith(out);
    }
}
