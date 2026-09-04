package com.condense.filter.build;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.BoundedRegex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("gradle"),
    @CommandFilter("./gradlew")
})
@ApplicationScoped
public class GradleFilter extends PipelineBackedFilter {

    public GradleFilter() {
        super();
    }

    @Inject
    public GradleFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.of(GradleSummaryStage.INSTANCE);
    }

    static final class GradleSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final GradleSummaryStage INSTANCE = new GradleSummaryStage();
        private static final Pattern BUILD_SUCCESSFUL = Pattern.compile("BUILD SUCCESSFUL");
        private static final Pattern BUILD_FAILED = Pattern.compile("BUILD FAILED");
        private static final Pattern FAILURE_DETAIL = Pattern.compile("^> ", Pattern.MULTILINE);

        @Override
        public StageResult process(String raw, FilterContext context) {
            if (BoundedRegex.find(BUILD_SUCCESSFUL, raw)) {
                String duration = raw.lines()
                    .filter(l -> l.contains("BUILD SUCCESSFUL"))
                    .findFirst().map(String::trim).orElse("BUILD SUCCESSFUL");
                return StageResult.continueWith("✓ " + duration);
            }
            if (BoundedRegex.find(BUILD_FAILED, raw)) {
                List<String> details = raw.lines()
                    .filter(l -> BoundedRegex.find(FAILURE_DETAIL, l) || l.startsWith("FAILURE:"))
                    .limit(15)
                    .toList();
                return StageResult.continueWith("✗ BUILD FAILED\n" + String.join("\n", details));
            }
            return StageResult.continueWith(context.result() != null ? context.result().combined() : raw);
        }
    }
}
