package com.condense.filter.build;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilters({
    @CommandFilter("gradle"),
    @CommandFilter("./gradlew")
})
@ApplicationScoped
public class GradleFilter extends PipelineBackedFilter {

    public GradleFilter() {
        super();
    }

    @Inject
    public GradleFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String definitionName() {
        return "gradle";
    }
}
