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

@CommandFilter("git push")
@ApplicationScoped
public class GitPushFilter extends PipelineBackedFilter {

    public GitPushFilter() {
        super();
    }

    @Inject
    public GitPushFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String selectInput(String command, ExecutionResult result,
                                 CondenseConfig config, int verbose, boolean ultraCompact) {
        return result.combined();
    }

    @Override
    protected FilterResult beforePipeline(String command, ExecutionResult result,
                                         CondenseConfig config, int verbose, boolean ultraCompact) {
        String raw = result.combined();
        if (BoundedRegex.find(GitPushSummaryStage.REJECTED, raw)) {
            return FilterResult.passthrough(result);
        }
        return null;
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(GitPushSummaryStage.INSTANCE);
    }

    static final class GitPushSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final GitPushSummaryStage INSTANCE = new GitPushSummaryStage();
        static final Pattern BRANCH_PATTERN = Pattern.compile("\\s+(\\S+)\\s+->\\s+(\\S+)");
        static final Pattern UP_TO_DATE = Pattern.compile("Everything up-to-date", Pattern.CASE_INSENSITIVE);
        static final Pattern REJECTED = Pattern.compile("\\[rejected\\]|error:|failed to push");

        @Override
        public StageResult process(String raw, FilterContext context) {
            if (BoundedRegex.find(UP_TO_DATE, raw)) {
                return StageResult.continueWith("✓ up-to-date (nothing pushed)");
            }
            Matcher m = BoundedRegex.matcher(BRANCH_PATTERN, raw);
            if (m.find()) {
                return StageResult.continueWith("✓ pushed → " + m.group(2).trim());
            }
            ExecutionResult result = context.result();
            if (result != null && result.succeeded()) {
                return StageResult.continueWith("✓ pushed");
            }
            return StageResult.continueWith(result != null ? result.combined() : raw);
        }
    }
}
