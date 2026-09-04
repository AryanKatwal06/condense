package com.condense.filter.golang;

import com.condense.annotation.CommandFilter;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilter("golangci-lint run")
@ApplicationScoped
public class GolangciLintFilter extends PipelineBackedFilter {

    public GolangciLintFilter() {
        super();
    }

    @Inject
    public GolangciLintFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String definitionName() {
        return "golangci-lint";
    }
}
