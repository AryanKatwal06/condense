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

@CommandFilter("jest")
@ApplicationScoped
public class JestFilter extends PipelineBackedFilter {

    public JestFilter() {
        super();
    }

    @Inject
    public JestFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String selectInput(String command, ExecutionResult result,
                                 CondenseConfig config, int verbose, boolean ultraCompact) {
        return result.hasStderr() ? result.readStderr() : result.readStdout();
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(JestSummaryStage.INSTANCE);
    }

    static final class JestSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final JestSummaryStage INSTANCE = new JestSummaryStage();
        private static final Pattern FAIL_SUITE = Pattern.compile("^\\s*FAIL\\s+(.+)$");
        private static final Pattern SUMMARY = Pattern.compile("^Tests:\\s+");
        private static final Pattern TEST_SUITES = Pattern.compile("^Test Suites:");

        @Override
        public StageResult process(String raw, FilterContext context) {
            List<String> failedSuites = new ArrayList<>();
            List<String> summaryLines = new ArrayList<>();
            for (String line : raw.lines().toList()) {
                var fm = BoundedRegex.matcher(FAIL_SUITE, line);
                if (fm.find()) {
                    failedSuites.add("  FAIL: " + fm.group(1).trim());
                    continue;
                }
                if (BoundedRegex.find(SUMMARY, line) || BoundedRegex.find(TEST_SUITES, line)) {
                    summaryLines.add(line.trim());
                }
            }

            ExecutionResult result = context.result();
            if (failedSuites.isEmpty() && summaryLines.isEmpty()) {
                if (result != null && result.succeeded()) {
                    return StageResult.continueWith("✓ all tests passed");
                }
                return StageResult.continueWith(result != null ? result.combined() : raw);
            }
            if (failedSuites.isEmpty() && result != null && result.succeeded()) {
                return StageResult.continueWith(String.join("\n", summaryLines));
            }

            StringBuilder sb = new StringBuilder();
            if (!failedSuites.isEmpty()) {
                CondenseConfig config = context.config();
                int limit = config != null
                    ? config.commandConfig("jest").maxFailures(Integer.MAX_VALUE)
                    : Integer.MAX_VALUE;
                List<String> shown = failedSuites.size() > limit
                    ? failedSuites.subList(0, limit) : failedSuites;
                sb.append("jest: ").append(failedSuites.size()).append(" suite(s) failed\n");
                shown.forEach(l -> sb.append(l).append('\n'));
            }
            summaryLines.forEach(l -> sb.append(l).append('\n'));
            return StageResult.continueWith(sb.toString().stripTrailing());
        }
    }
}
