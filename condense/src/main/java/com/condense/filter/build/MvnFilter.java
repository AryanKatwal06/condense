package com.condense.filter.build;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
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

@CommandFilters({
    @CommandFilter("mvn"),
    @CommandFilter("./mvnw")
})
@ApplicationScoped
public class MvnFilter extends PipelineBackedFilter {

    public MvnFilter() {
        super();
    }

    @Inject
    public MvnFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String selectInput(String command, ExecutionResult result,
                                 CondenseConfig config, int verbose, boolean ultraCompact) {
        return result.hasStderr() ? result.readStderr() : result.readStdout();
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(MvnSummaryStage.INSTANCE);
    }

    static final class MvnSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final MvnSummaryStage INSTANCE = new MvnSummaryStage();
        private static final Pattern BUILD_SUCCESS = Pattern.compile("BUILD SUCCESS");
        private static final Pattern BUILD_FAILURE = Pattern.compile("BUILD FAILURE");
        private static final Pattern ERROR_LINE = Pattern.compile("^\\[ERROR\\]");
        private static final Pattern TEST_FAIL = Pattern.compile("Tests run:.+Failures: [1-9]");

        @Override
        public StageResult process(String raw, FilterContext context) {
            boolean isSuccess = false;
            boolean isFailure = false;
            String testLine = "";
            List<String> errors = new ArrayList<>();

            for (String line : raw.lines().toList()) {
                if (BoundedRegex.find(BUILD_SUCCESS, line)) {
                    isSuccess = true;
                }
                if (BoundedRegex.find(BUILD_FAILURE, line)) {
                    isFailure = true;
                }
                if (line.contains("Tests run:")) {
                    testLine = line;
                }
                if (BoundedRegex.find(ERROR_LINE, line) || BoundedRegex.find(TEST_FAIL, line)) {
                    errors.add(line.trim());
                }
            }

            if (isSuccess) {
                String tl = testLine.trim();
                CondenseConfig config = context.config();
                if (!tl.isBlank() && config != null
                    && !config.commandConfig("mvn").showTiming(true)
                    && tl.contains(", Time elapsed:")) {
                    tl = tl.substring(0, tl.indexOf(", Time elapsed:")).trim();
                }
                return StageResult.continueWith("✓ BUILD SUCCESS" + (tl.isBlank() ? "" : " — " + tl));
            }

            if (isFailure) {
                errors = errors.subList(0, Math.min(20, errors.size()));
                return StageResult.continueWith("✗ BUILD FAILURE\n" + String.join("\n", errors));
            }

            ExecutionResult result = context.result();
            return StageResult.continueWith(result != null ? result.combined() : raw);
        }
    }
}
