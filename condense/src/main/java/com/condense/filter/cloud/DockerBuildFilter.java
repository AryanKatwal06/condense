package com.condense.filter.cloud;

import com.condense.annotation.CommandFilter;
import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterResult;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.strategy.AnsiStripStrategy;
import com.condense.filter.strategy.BoundedRegex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("docker build")
@ApplicationScoped
public class DockerBuildFilter extends PipelineBackedFilter {

    public DockerBuildFilter() {
        super();
    }

    @Inject
    public DockerBuildFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected FilterResult beforePipeline(String command, ExecutionResult result,
                                         CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) {
            return FilterResult.passthrough(result);
        }
        return null;
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.builder()
            .addStage(AnsiStripStrategy.INSTANCE)
            .addStage(DockerBuildSummaryStage.INSTANCE)
            .build();
    }

    static final class DockerBuildSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final DockerBuildSummaryStage INSTANCE = new DockerBuildSummaryStage();
        private static final Pattern IMAGE_ID =
            Pattern.compile("(?:Successfully built|writing image sha256:)\\s*([0-9a-f]{8,12})");
        private static final Pattern TAGGED =
            Pattern.compile("Successfully tagged (.+)");

        @Override
        public StageResult process(String clean, FilterContext context) {
            StringBuilder sb = new StringBuilder("✓ docker build");
            Matcher id = BoundedRegex.matcher(IMAGE_ID, clean);
            if (id.find()) {
                sb.append(": ").append(id.group(1));
            }
            Matcher tag = BoundedRegex.matcher(TAGGED, clean);
            if (tag.find()) {
                sb.append(" → ").append(tag.group(1).trim());
            }
            return StageResult.continueWith(sb.toString());
        }
    }
}
