package com.condense.filter.fs;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilter("cat")
@ApplicationScoped
public class CatFilter extends PipelineBackedFilter {

    private static final int CHAR_LIMIT_BEFORE_COMPRESS = 2000;

    public CatFilter() {
        super();
    }

    @Inject
    public CatFilter(FilterOverrideLoader overrideLoader) {
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
        long size = 0;
        try {
            size = java.nio.file.Files.size(result.stdoutFile());
        } catch (java.io.IOException e) {
            return FilterResult.passthrough(result);
        }
        if (size <= CHAR_LIMIT_BEFORE_COMPRESS || verbose >= 2) {
            return FilterResult.passthrough(result);
        }
        return null;
    }

    @Override
    protected String definitionName() {
        return "cat";
    }
}
