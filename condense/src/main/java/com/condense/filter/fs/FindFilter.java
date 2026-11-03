package com.condense.filter.fs;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.HeadTailStage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@CommandFilter("find")
@ApplicationScoped
public class FindFilter extends PipelineBackedFilter {

    /**
     * Results at or below this many lines pass through verbatim so the actual
     * file paths stay visible instead of collapsing to an extension count.
     */
    static final int SMALL_RESULT_LINES = 40;

    private static final HeadTailStage WINDOW = new HeadTailStage(60, 20);

    public FindFilter() {
        super();
    }

    @Inject
    public FindFilter(FilterOverrideLoader overrideLoader) {
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
        List<String> lines = result.readStdout().lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) {
            return FilterResult.of(result, "(no results)");
        }
        if (lines.size() <= SMALL_RESULT_LINES || verbose >= 2) {
            return FilterResult.passthrough(result);
        }
        String windowed = WINDOW.process(result.readStdout(), FilterContext.empty()).output();
        return FilterResult.of(result, lines.size() + " result(s)\n" + windowed);
    }

    @Override
    protected String definitionName() {
        return "find";
    }
}
