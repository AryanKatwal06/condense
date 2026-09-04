package com.condense.filter.node;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
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

import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("npm install"),
    @CommandFilter("npm ci"),
    @CommandFilter("npm i")
})
@ApplicationScoped
public class NpmInstallFilter extends PipelineBackedFilter {

    public NpmInstallFilter() {
        super();
    }

    @Inject
    public NpmInstallFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected FilterResult beforePipeline(String command, ExecutionResult result,
                                         com.condense.core.CondenseConfig config,
                                         int verbose, boolean ultraCompact) {
        if (!result.succeeded()) {
            return FilterResult.passthrough(result);
        }
        return null;
    }

    @Override
    protected FilterPipeline buildPipeline() {
        return FilterPipeline.builder()
            .addStage(AnsiStripStrategy.INSTANCE)
            .addStage(NpmInstallSummaryStage.INSTANCE)
            .build();
    }

    static final class NpmInstallSummaryStage implements com.condense.filter.pipeline.FilterStage {
        static final NpmInstallSummaryStage INSTANCE = new NpmInstallSummaryStage();
        private static final Pattern ADDED_PATTERN = Pattern.compile("added (\\d+) packages?");
        private static final Pattern AUDIT_PATTERN = Pattern.compile("found (\\d+) vulnerabilit");

        @Override
        public StageResult process(String input, FilterContext context) {
            var added = BoundedRegex.matcher(ADDED_PATTERN, input);
            var audit = BoundedRegex.matcher(AUDIT_PATTERN, input);
            StringBuilder sb = new StringBuilder("✓ npm install");
            if (added.find()) {
                sb.append(": ").append(added.group(1)).append(" packages");
            }
            if (audit.find()) {
                sb.append(" | ").append(audit.group(0));
            }
            return StageResult.continueWith(sb.toString());
        }
    }
}
