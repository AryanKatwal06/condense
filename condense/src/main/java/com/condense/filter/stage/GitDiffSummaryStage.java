package com.condense.filter.stage;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.BoundedRegex;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GitDiffSummaryStage implements FilterStage {
    public static final GitDiffSummaryStage INSTANCE = new GitDiffSummaryStage();
    private static final Pattern STAT_SUMMARY = Pattern.compile("(\\d+) files? changed.*");
    private static final Pattern STAT_FILE_LINE = Pattern.compile("^\\s+\\S.*\\|\\s*\\d+");

    private GitDiffSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        Matcher m = BoundedRegex.matcher(STAT_SUMMARY, raw);
        if (m.find()) {
            String summary = m.group(0).trim();
            if (context.verbose() >= 2) {
                StringBuilder sb = new StringBuilder(summary).append('\n');
                raw.lines()
                    .filter(l -> BoundedRegex.find(STAT_FILE_LINE, l))
                    .forEach(l -> sb.append("  ").append(l.trim()).append('\n'));
                return StageResult.continueWith(sb.toString().stripTrailing());
            }
            return StageResult.continueWith(summary);
        }

        long added = raw.lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).count();
        long removed = raw.lines().filter(l -> l.startsWith("-") && !l.startsWith("---")).count();
        if (added == 0 && removed == 0) {
            return StageResult.continueWith("no changes");
        }
        return StageResult.continueWith("+" + added + " / -" + removed + " lines");
    }
}
