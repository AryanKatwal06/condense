package com.condense.filter.git;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.BoundedRegex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("git diff")
@ApplicationScoped
public class GitDiffFilter extends PipelineBackedFilter {

    public GitDiffFilter() {
        super();
    }

    @Inject
    public GitDiffFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected FilterResult beforePipeline(String command, ExecutionResult result,
                                         CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded() && result.readStdout().isBlank()) {
            return FilterResult.passthrough(result);
        }
        return null;
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(GitDiffSummaryStage.INSTANCE);
    }

    static final class GitDiffSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final GitDiffSummaryStage INSTANCE = new GitDiffSummaryStage();
        private static final Pattern STAT_SUMMARY = Pattern.compile("(\\d+) files? changed.*");
        private static final Pattern STAT_FILE_LINE = Pattern.compile("^\\s+\\S.*\\|\\s*\\d+");

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
}
