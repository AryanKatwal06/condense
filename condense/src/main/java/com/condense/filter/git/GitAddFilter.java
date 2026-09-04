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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilter("git add")
@ApplicationScoped
public class GitAddFilter extends PipelineBackedFilter {

    public GitAddFilter() {
        super();
    }

    @Inject
    public GitAddFilter(FilterOverrideLoader overrideLoader) {
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
        return FilterPipeline.of(GitAddSummaryStage.INSTANCE);
    }

    static final class GitAddSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final GitAddSummaryStage INSTANCE = new GitAddSummaryStage();

        @Override
        public StageResult process(String raw, FilterContext context) {
            if (raw.isBlank()) {
                return StageResult.continueWith("✓ staged");
            }
            long fileCount = raw.lines().filter(l -> !l.isBlank()).count();
            return StageResult.continueWith("✓ staged " + fileCount + " file(s)");
        }
    }
}
