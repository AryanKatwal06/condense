package com.condense.filter.node;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.*;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.AnsiStripStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("npm install"),
    @CommandFilter("npm ci"),
    @CommandFilter("npm i")
})
@ApplicationScoped
public class NpmInstallFilter implements FilterStrategy {

    private static final Pattern ADDED_PATTERN =
        Pattern.compile("added (\\d+) packages?");
    private static final Pattern AUDIT_PATTERN =
        Pattern.compile("found (\\d+) vulnerabilit");

    private final FilterPipeline pipeline;

    public NpmInstallFilter() {
        this.pipeline = FilterPipeline.builder()
            .addStage(AnsiStripStrategy.INSTANCE)
            .addStage((clean, ctx) -> {
                Matcher added = ADDED_PATTERN.matcher(clean);
                Matcher audit = AUDIT_PATTERN.matcher(clean);

                StringBuilder sb = new StringBuilder("✓ npm install");
                if (added.find()) sb.append(": ").append(added.group(1)).append(" packages");
                if (audit.find()) sb.append(" | ").append(audit.group(0));

                return StageResult.continueWith(sb.toString());
            })
            .build();
    }

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result);

        String raw = result.readStdout().isBlank() ? result.readStderr() : result.readStdout();
        FilterContext context = FilterContext.of(command, result, config, verbose, ultraCompact);
        String output = pipeline.execute(raw, context);
        return FilterResult.of(result, output);
    }
}