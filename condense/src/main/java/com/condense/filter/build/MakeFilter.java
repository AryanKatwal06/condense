package com.condense.filter.build;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@CommandFilter("make")
@ApplicationScoped
public class MakeFilter extends PipelineBackedFilter {

    public MakeFilter() {
        super();
    }

    @Inject
    public MakeFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String selectInput(String command, ExecutionResult result,
                                 CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) {
            return result.combined();
        }
        return result.readStdout();
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(MakeSummaryStage.INSTANCE);
    }

    static final class MakeSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final MakeSummaryStage INSTANCE = new MakeSummaryStage();

        @Override
        public StageResult process(String raw, FilterContext context) {
            ExecutionResult result = context.result();
            if (result != null && !result.succeeded()) {
                List<String> errors = raw.lines()
                    .filter(l -> l.startsWith("make") || l.contains("Error") || l.contains("error:"))
                    .limit(15)
                    .toList();
                return StageResult.continueWith(errors.isEmpty() ? raw : String.join("\n", errors));
            }
            String lastLine = raw.lines().filter(l -> !l.isBlank()).reduce("", (a, b) -> b);
            return StageResult.continueWith("✓ make: " + (lastLine.isBlank() ? "done" : lastLine.trim()));
        }
    }
}
