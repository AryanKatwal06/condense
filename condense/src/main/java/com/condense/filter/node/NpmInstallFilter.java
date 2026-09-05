package com.condense.filter.node;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilters({
    @CommandFilter("npm install"),
    @CommandFilter("npm ci"),
    @CommandFilter("npm i")
})
@ApplicationScoped
public class NpmInstallFilter extends PipelineBackedFilter {

    public NpmInstallFilter() {
        super();
    }

    @Inject
    public NpmInstallFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected FilterResult beforePipeline(String command, ExecutionResult result,
                                         CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) {
            String selected = selectInput(command, result, config, verbose, ultraCompact);
            boolean hasSignal = selected.lines().anyMatch(line ->
                line.regionMatches(true, 0, "npm warn", 0, 8)
                    || line.regionMatches(true, 0, "npm ERR!", 0, 8));
            if (!hasSignal) {
                return FilterResult.passthrough(result);
            }
        }
        return null;
    }

    @Override
    protected String selectInput(String command, ExecutionResult result,
                                 CondenseConfig config, int verbose, boolean ultraCompact) {
        return stderrThenStdout(result);
    }

    @Override
    protected String definitionName() {
        return "npm-install";
    }
}
