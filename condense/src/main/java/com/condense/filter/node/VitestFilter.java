package com.condense.filter.node;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.BoundedRegex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilter("vitest")
@ApplicationScoped
public class VitestFilter extends PipelineBackedFilter {

    public VitestFilter() {
        super();
    }

    @Inject
    public VitestFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String selectInput(String command, ExecutionResult result,
                                 CondenseConfig config, int verbose, boolean ultraCompact) {
        return result.hasStderr() ? result.readStderr() : result.readStdout();
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(VitestSummaryStage.INSTANCE);
    }

    static final class VitestSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final VitestSummaryStage INSTANCE = new VitestSummaryStage();
        private static final Pattern FAIL_LINE = Pattern.compile("×|✗|FAIL", Pattern.UNICODE_CASE);
        private static final Pattern SUMMARY_LINE = Pattern.compile("Tests\\s+\\d+");

        @Override
        public StageResult process(String raw, FilterContext context) {
            List<String> failures = new ArrayList<>();
            List<String> summary = new ArrayList<>();
            for (String line : raw.lines().toList()) {
                if (BoundedRegex.find(FAIL_LINE, line)
                    && !line.contains("passed") && !line.isBlank()) {
                    failures.add("  " + line.trim());
                } else if (BoundedRegex.find(SUMMARY_LINE, line)) {
                    summary.add(line.trim());
                }
            }

            ExecutionResult result = context.result();
            if (failures.isEmpty() && summary.isEmpty()) {
                if (result != null && result.succeeded()) {
                    return StageResult.continueWith("✓ all tests passed");
                }
                return StageResult.continueWith(result != null ? result.combined() : raw);
            }
            if (failures.isEmpty() && result != null && result.succeeded()) {
                return StageResult.continueWith(String.join("\n", summary));
            }

            StringBuilder sb = new StringBuilder();
            if (!failures.isEmpty()) {
                sb.append("vitest: ").append(failures.size()).append(" failure(s)\n");
                failures.stream().limit(20).forEach(l -> sb.append(l).append('\n'));
            }
            summary.forEach(l -> sb.append(l).append('\n'));
            return StageResult.continueWith(sb.toString().stripTrailing());
        }
    }
}
