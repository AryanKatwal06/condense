package com.condense.filter.fs;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.AggregateByKeyStage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilters({
    @CommandFilter("grep"),
    @CommandFilter("rg")
})
@ApplicationScoped
public class GrepFilter extends PipelineBackedFilter {

    public GrepFilter() {
        super();
    }

    @Inject
    public GrepFilter(FilterOverrideLoader overrideLoader) {
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
        if (result.exitCode() == 1) {
            return FilterResult.of(result, "(no matches)");
        }
        if (!result.succeeded()) {
            return FilterResult.passthrough(result);
        }
        long lineCount = result.readStdout().lines().filter(l -> !l.isBlank()).count();
        if (lineCount == 0) {
            return FilterResult.of(result, "(no matches)");
        }
        if (lineCount <= 10 || verbose >= 2) {
            return FilterResult.passthrough(result);
        }
        return null;
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(new AggregateByKeyStage(
            line -> {
                int colon = line.indexOf(':');
                return colon > 0 ? line.substring(0, colon) : "(stdin)";
            },
            (lines, keys) -> lines + " match(es) in " + keys + " file(s)",
            10
        ));
    }
}
