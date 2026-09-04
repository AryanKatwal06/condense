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

@CommandFilter("git commit")
@ApplicationScoped
public class GitCommitFilter extends PipelineBackedFilter {

    public GitCommitFilter() {
        super();
    }

    @Inject
    public GitCommitFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String selectInput(String command, ExecutionResult result,
                                 CondenseConfig config, int verbose, boolean ultraCompact) {
        return result.readStdout();
    }

    @Override
    protected FilterResult beforePipeline(String command, ExecutionResult result,
                                         CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) {
            return FilterResult.passthrough(result);
        }
        return null;
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(GitCommitSummaryStage.INSTANCE);
    }

    static final class GitCommitSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final GitCommitSummaryStage INSTANCE = new GitCommitSummaryStage();
        private static final Pattern COMMIT_LINE =
            Pattern.compile("^\\[([^\\]]+)\\s+([0-9a-f]+)\\]\\s+(.+)$", Pattern.MULTILINE);

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
}
