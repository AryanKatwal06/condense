package com.condense.filter.node;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilters({
    @CommandFilter("eslint"),
    @CommandFilter("npx eslint")
})
@ApplicationScoped
public class ESLintFilter extends PipelineBackedFilter {

    public ESLintFilter() {
        super();
    }

    @Inject
    public ESLintFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String definitionName() {
        return "eslint";
    }
}
