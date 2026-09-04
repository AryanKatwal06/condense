package com.condense.filter.git;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.stage.GitPushSummaryStage;
import com.condense.filter.strategy.BoundedRegex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
    protected String definitionName() {
        return "git-push";
    }
}
