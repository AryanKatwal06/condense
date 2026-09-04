package com.condense.filter.fs;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.TreeCompressionStrategy;
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
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.builder()
            .addStage(TreeCompressionStrategy.INSTANCE)
            .addStage(LsEmptyTreeFallbackStage.INSTANCE)
            .build();
    }

    static final class LsEmptyTreeFallbackStage implements com.condense.filter.pipeline.FilterStage {
        static final LsEmptyTreeFallbackStage INSTANCE = new LsEmptyTreeFallbackStage();

        @Override
        public StageResult process(String tree, FilterContext ctx) {
            if (tree.isBlank()) {
                long count = 0;
                if (ctx.result() != null) {
                    try (java.util.stream.Stream<String> stream = ctx.result().stdoutLines()) {
                        count = stream.filter(l -> !l.isBlank()).count();
                    } catch (Exception ignored) {
                    }
                }
                return StageResult.continueWith(count + " items");
            }
            return StageResult.continueWith(tree);
        }
    }
}
