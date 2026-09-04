package com.condense.filter.cloud;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilter("kubectl")
@ApplicationScoped
public class KubectlFilter extends PipelineBackedFilter {

    public KubectlFilter() {
        super();
    }

    @Inject
    public KubectlFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String selectInput(String command, ExecutionResult result,
                                 CondenseConfig config, int verbose, boolean ultraCompact) {
        return result.readStdout();
    }

    @Override
    protected FilterResult beforePipeline(String command, ExecutionResult result,
                                         CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) {
            return FilterResult.passthrough(result);
        }
        if (result.readStdout().lines().toList().isEmpty()) {
            return FilterResult.passthrough(result);
        }
        if (command.contains("get") || command.contains("describe") || command.contains("logs")) {
            return null;
        }
        return FilterResult.passthrough(result);
    }

    @Override
    protected String definitionName() {
        return "kubectl";
    }
}
