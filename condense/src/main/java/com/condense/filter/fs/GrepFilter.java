package com.condense.filter.fs;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
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

@CommandFilters({
    @CommandFilter("grep"),
    @CommandFilter("rg")
})
@ApplicationScoped
public class GrepFilter extends PipelineBackedFilter {

    /**
     * Results at or below this many lines pass through verbatim. Search output
     * exists to show what matched, so small results must never collapse to a count.
     */
    static final int SMALL_RESULT_LINES = 40;

    private static final HeadTailStage WINDOW = new HeadTailStage(60, 20);

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
        List<String> lines = result.readStdout().lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) {
            return FilterResult.of(result, "(no matches)");
        }
        if (lines.size() <= SMALL_RESULT_LINES || verbose >= 2) {
            return FilterResult.passthrough(result);
        }
        String windowed = WINDOW.process(result.readStdout(), FilterContext.empty()).output();
        long files = lines.stream().map(GrepFilter::fileKey).distinct().count();
        String header = lines.size() + " match(es) in " + files + " file(s)";
        return FilterResult.of(result, header + "\n" + windowed);
    }

    private static String fileKey(String line) {
        int colon = line.indexOf(':');
        return colon > 0 ? line.substring(0, colon) : line;
    }

    @Override
    protected String definitionName() {
        return "grep";
    }
}
