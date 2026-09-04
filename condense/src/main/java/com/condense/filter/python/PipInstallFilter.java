package com.condense.filter.python;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.AnsiStripStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@CommandFilters({
    @CommandFilter("pip install"),
    @CommandFilter("pip3 install")
})
@ApplicationScoped
public class PipInstallFilter extends PipelineBackedFilter {

    public PipInstallFilter() {
        super();
    }

    @Inject
    public PipInstallFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected FilterResult beforePipeline(String command, ExecutionResult result,
                                         CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) {
            return FilterResult.passthrough(result);
        }
        return null;
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.builder()
            .addStage(AnsiStripStrategy.INSTANCE)
            .addStage(PipInstallSummaryStage.INSTANCE)
            .build();
    }

    static final class PipInstallSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final PipInstallSummaryStage INSTANCE = new PipInstallSummaryStage();

        @Override
        public StageResult process(String clean, FilterContext context) {
            List<String> installed = clean.lines()
                .filter(l -> l.startsWith("Successfully installed"))
                .toList();
            if (installed.isEmpty()) {
                String lastLine = AnsiStripStrategy.lastMeaningfulLine(clean);
                return StageResult.continueWith(lastLine.isBlank() ? "✓ pip install" : lastLine);
            }
            return StageResult.continueWith(installed.get(installed.size() - 1).trim());
        }
    }
}
