package com.condense.filter.cargo;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
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

@CommandFilter("cargo clippy")
@ApplicationScoped
public class CargoClippyFilter extends PipelineBackedFilter {

    public CargoClippyFilter() {
        super();
    }

    @Inject
    public CargoClippyFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String selectInput(String command, ExecutionResult result,
                                 CondenseConfig config, int verbose, boolean ultraCompact) {
        return result.hasStderr() ? result.readStderr() : result.readStdout();
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(CargoClippySummaryStage.INSTANCE);
    }

    static final class CargoClippySummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final CargoClippySummaryStage INSTANCE = new CargoClippySummaryStage();
        private static final Pattern WARNING_RULE =
            Pattern.compile("^warning: (.+)$", Pattern.MULTILINE);
        private static final Pattern LINT_NAME =
            Pattern.compile("#\\[warn\\((.+?)\\)\\]");

        @Override
        public StageResult process(String raw, FilterContext context) {
            List<String> lines = raw.lines().toList();
            Map<String, Integer> groups = GroupingStrategy.group(lines, LINT_NAME, false);
            if (groups.isEmpty()) {
                groups = GroupingStrategy.group(lines, WARNING_RULE, false);
            }
            long warnings = groups.values().stream().mapToLong(Integer::longValue).sum();
            ExecutionResult result = context.result();
            if (warnings == 0 && result != null && result.succeeded()) {
                return StageResult.continueWith("✓ no clippy warnings");
            }
            StringBuilder sb = new StringBuilder("cargo clippy: ")
                .append(warnings).append(" warning(s)\n");
            sb.append(GroupingStrategy.format(groups));
            return StageResult.continueWith(sb.toString().stripTrailing());
        }
    }
}
