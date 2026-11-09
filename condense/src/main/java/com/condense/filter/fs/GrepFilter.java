package com.condense.filter.fs;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;


@CommandFilters({
    @CommandFilter("grep"),
    @CommandFilter("rg")
})
@ApplicationScoped
public class GrepFilter extends PipelineBackedFilter {

    static final int SMALL_RESULT_LINES = 40;

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
        if (result.readStdout().isBlank()) {
            return FilterResult.of(result, "(no matches)");
        }
        List<String> lines = result.readStdout().lines().filter(line -> !line.isBlank()).toList();
        if (lines.size() <= SMALL_RESULT_LINES || verbose >= 2) {
            return FilterResult.passthrough(result);
        }
        return null;
    }

    @Override
    protected String definitionName() {
        return "grep";
    }
}
