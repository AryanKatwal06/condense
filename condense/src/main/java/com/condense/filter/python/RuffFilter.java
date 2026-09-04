package com.condense.filter.python;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilters({
    @CommandFilter("ruff check"),
    @CommandFilter("ruff")
})
@ApplicationScoped
public class RuffFilter extends PipelineBackedFilter {

    public RuffFilter() {
        super();
    }

    @Inject
    public RuffFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String definitionName() {
        return "ruff";
    }
}
