package com.condense.filter.fs;

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
import com.condense.filter.strategy.HeadTailStage;
import com.condense.filter.strategy.JsonStructureStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilters({
    @CommandFilter("cat"),
    @CommandFilter("read")
})
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
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(CatContentStage.INSTANCE);
    }

    static final class CatContentStage implements com.condense.filter.pipeline.FilterStage {
        static final CatContentStage INSTANCE = new CatContentStage();
        private static final HeadTailStage HEAD_TAIL = new HeadTailStage(20, 20);

        @Override
        public StageResult process(String raw, FilterContext context) {
            java.util.List<String> lines = raw.lines().toList();
            if (!lines.isEmpty()) {
                String trimmed = lines.get(0).trim();
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    try {
                        String skeleton = JsonStructureStrategy.skeleton(raw.trim());
                        return StageResult.continueWith(skeleton);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (lines.size() > 40) {
                return HEAD_TAIL.process(raw, context);
            }
            return StageResult.continueWith(raw);
        }
    }
}
