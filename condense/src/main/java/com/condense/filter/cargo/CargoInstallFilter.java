package com.condense.filter.cargo;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilters({
    @CommandFilter("cargo install"),
    @CommandFilter("cargo build")
})
@ApplicationScoped
public class CargoInstallFilter extends PipelineBackedFilter {

    public CargoInstallFilter() {
        super();
    }

    @Inject
    public CargoInstallFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String definitionName() {
        return "cargo-install";
    }
}
