package com.condense.filter.cloud;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.JsonStructureStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilter("aws")
@ApplicationScoped
public class AwsFilter extends PipelineBackedFilter {

    public AwsFilter() {
        super();
    }

    @Inject
    public AwsFilter(FilterOverrideLoader overrideLoader) {
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
        String raw = result.readStdout();
        if (raw.isBlank()) {
            return FilterResult.of(result, "✓ ok");
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            if (raw.length() > 500 || verbose < 2) {
                return null;
            }
        }
        return FilterResult.passthrough(result);
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(JsonStructureStrategy.INSTANCE);
    }
}
