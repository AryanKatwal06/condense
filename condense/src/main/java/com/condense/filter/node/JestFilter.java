package com.condense.filter.node;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilter("jest")
@ApplicationScoped
public class JestFilter extends PipelineBackedFilter {

    public JestFilter() {
        super();
    }

    @Inject
    public JestFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String selectInput(String command, ExecutionResult result,
                                 CondenseConfig config, int verbose, boolean ultraCompact) {
        return result.hasStderr() ? result.readStderr() : result.readStdout();
    }

    @Override
    protected String definitionName() {
        return "jest";
    }
}
