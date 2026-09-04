package com.condense.filter.build;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilter("make")
@ApplicationScoped
public class MakeFilter extends PipelineBackedFilter {

    public MakeFilter() {
        super();
    }

    @Inject
    public MakeFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String selectInput(String command, ExecutionResult result,
                                 CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) {
            return result.combined();
        }
        return result.readStdout();
    }

    @Override
    protected String definitionName() {
        return "make";
    }
}
