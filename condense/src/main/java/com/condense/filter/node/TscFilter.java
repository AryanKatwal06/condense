package com.condense.filter.node;

import com.condense.annotation.CommandFilter;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilter("tsc")
@ApplicationScoped
public class TscFilter extends PipelineBackedFilter {

    public TscFilter() {
        super();
    }

    @Inject
    public TscFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String definitionName() {
        return "tsc";
    }
}
