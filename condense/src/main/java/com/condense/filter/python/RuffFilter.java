package com.condense.filter.python;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.GroupingStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("ruff check"),
    @CommandFilter("ruff")
})
@ApplicationScoped
public class RuffFilter extends PipelineBackedFilter {

    public RuffFilter() {
        super();
    }

    @Inject
    public RuffFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(RuffSummaryStage.INSTANCE);
    }

    static final class RuffSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final RuffSummaryStage INSTANCE = new RuffSummaryStage();
        private static final Pattern RULE_PATTERN = Pattern.compile(":\\s+([A-Z]\\d+)\\s");

        @Override
        public StageResult process(String raw, FilterContext context) {
            List<String> lines = raw.lines().toList();
            Map<String, Integer> groups = GroupingStrategy.group(lines, RULE_PATTERN, false);
            long total = groups.values().stream().mapToLong(Integer::longValue).sum();
            ExecutionResult result = context.result();
            if (total == 0 && result != null && result.succeeded()) {
                return StageResult.continueWith("✓ no lint issues");
            }
            StringBuilder sb = new StringBuilder("ruff: ").append(total).append(" issue(s)\n");
            sb.append(GroupingStrategy.format(groups));
            return StageResult.continueWith(sb.toString().stripTrailing());
        }
    }
}
