package com.condense.filter.git;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
    protected String definitionName() {
        return "git-diff";
    }
}
