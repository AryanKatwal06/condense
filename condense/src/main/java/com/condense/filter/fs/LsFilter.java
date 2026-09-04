package com.condense.filter.fs;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@CommandFilter("ls")
@ApplicationScoped
public class LsFilter extends PipelineBackedFilter {

    public LsFilter() {
        super();
    }

    @Inject
    public LsFilter(FilterOverrideLoader overrideLoader) {
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
            return FilterResult.of(result, "(empty directory)");
        }
        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) {
            return FilterResult.of(result, "(empty directory)");
        }
        if (lines.size() <= 10 || verbose >= 2) {
            return FilterResult.passthrough(result);
        }
        return null;
    }

    @Override
    protected String definitionName() {
        return "ls";
    }
}
