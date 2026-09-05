package com.condense.filter.cloud;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilter("docker build")
@ApplicationScoped
public class DockerBuildFilter extends PipelineBackedFilter {

    public DockerBuildFilter() {
        super();
    }

    @Inject
    public DockerBuildFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected FilterResult beforePipeline(String command, ExecutionResult result,
                                         CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) {
            String selected = selectInput(command, result, config, verbose, ultraCompact);
            boolean hasSignal = selected.lines().anyMatch(line ->
                line.startsWith("#") && line.contains("DONE")
                    || line.matches("(?i).*(ERROR|failed to).*"));
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
        return "docker-build";
    }
}
