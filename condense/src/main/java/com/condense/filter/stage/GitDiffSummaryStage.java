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

@DeclarativeStage(aliases = {"git_diff_summary"}, capability = "RESHAPE", singleton = "INSTANCE")
public final class GitDiffSummaryStage implements FilterStage {
    public static final GitDiffSummaryStage INSTANCE = new GitDiffSummaryStage();
    private static final Pattern STAT_SUMMARY = Pattern.compile("(\\d+) files? changed.*");
    private static final Pattern STAT_FILE_LINE = Pattern.compile("^\\s+\\S.*\\|\\s*\\d+");

    private GitDiffSummaryStage() {}

    @Override
    public StageResult process(String raw, FilterContext context) {
        String output;
        Matcher m = BoundedRegex.matcher(STAT_SUMMARY, raw);
        if (m.find()) {
            String summary = m.group(0).trim();
            if (context != null && context.verbose() >= 2) {
                StringBuilder sb = new StringBuilder(summary).append('\n');
                raw.lines()
                    .filter(l -> BoundedRegex.find(STAT_FILE_LINE, l))
                    .forEach(l -> sb.append("  ").append(l.trim()).append('\n'));
                output = sb.toString().stripTrailing();
            } else {
                output = summary;
            }
        } else {
            long added = raw.lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")).count();
            long removed = raw.lines().filter(l -> l.startsWith("-") && !l.startsWith("---")).count();
            if (added == 0 && removed == 0) {
                output = "no changes";
            } else {
                output = "+" + added + " / -" + removed + " lines";
            }
        }

        if (context != null && context.documentBuilder() != null) {
            context.documentBuilder().git(new Document.GitDocument(
                "",
                "no changes".equals(output),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                output,
                "diff",
                raw != null ? raw.lines().toList() : List.of()
            ));
        }

        return StageResult.continueWith(output);
    }
}
