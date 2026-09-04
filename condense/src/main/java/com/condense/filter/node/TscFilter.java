package com.condense.filter.node;

import com.condense.annotation.CommandFilter;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.BoundedRegex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("tsc")
@ApplicationScoped
public class TscFilter extends PipelineBackedFilter {

    public TscFilter() {
        super();
    }

    @Inject
    public TscFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(TscSummaryStage.INSTANCE);
    }

    static final class TscSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final TscSummaryStage INSTANCE = new TscSummaryStage();
        private static final Pattern FILE_PATTERN = Pattern.compile("^(\\S+\\.ts)\\(");

        @Override
        public StageResult process(String raw, FilterContext context) {
            List<String> lines = raw.lines().toList();
            Map<String, Integer> byFile = new LinkedHashMap<>();
            for (String line : lines) {
                Matcher m = BoundedRegex.matcher(FILE_PATTERN, line);
                if (m.find()) {
                    byFile.merge(m.group(1), 1, Integer::sum);
                }
            }
            ExecutionResult result = context.result();
            if (byFile.isEmpty() && result != null && result.succeeded()) {
                return StageResult.continueWith("✓ no type errors");
            }
            long total = byFile.values().stream().mapToLong(Integer::longValue).sum();
            StringBuilder sb = new StringBuilder("tsc: ").append(total).append(" error(s)\n");
            byFile.forEach((f, c) -> sb.append("  ").append(f).append(": ").append(c).append('\n'));
            return StageResult.continueWith(sb.toString().stripTrailing());
        }
    }
}
