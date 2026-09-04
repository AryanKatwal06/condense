package com.condense.filter.cloud;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.TailLinesStage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@CommandFilters({
    @CommandFilter("docker logs"),
    @CommandFilter("docker run"),
    @CommandFilter("docker exec")
})
@ApplicationScoped
public class DockerFilter extends PipelineBackedFilter {

    private static final int MAX_LOG_LINES = 30;

    public DockerFilter() {
        super();
    }

    @Inject
    public DockerFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected FilterResult beforePipeline(String command, ExecutionResult result,
                                         CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) {
            return FilterResult.passthrough(result);
        }
        String raw = result.readStdout().isBlank() ? result.readStderr() : result.readStdout();
        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();
        if (lines.size() <= MAX_LOG_LINES || verbose >= 2) {
            return FilterResult.passthrough(result);
        }
        return null;
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(new TailLinesStage(MAX_LOG_LINES, true, false));
    }
}
