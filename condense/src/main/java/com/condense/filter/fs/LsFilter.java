package com.condense.filter.fs;

import com.condense.annotation.CommandFilter;
import com.condense.core.*;
import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.pipeline.StageResult;
import com.condense.filter.strategy.TreeCompressionStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@CommandFilter("ls")
@ApplicationScoped
public class LsFilter implements FilterStrategy {

    private final FilterPipeline pipeline;

    public LsFilter() {
        this.pipeline = FilterPipeline.builder()
            .addStage(TreeCompressionStrategy.INSTANCE)
            .addStage((tree, ctx) -> {
                if (tree.isBlank()) {
                    long count = 0;
                    if (ctx.result() != null) {
                        try (java.util.stream.Stream<String> stream = ctx.result().stdoutLines()) {
                            count = stream.filter(l -> !l.isBlank()).count();
                        } catch (Exception ignored) {}
                    }
                    return StageResult.continueWith(count + " items");
                }
                return StageResult.continueWith(tree);
            })
            .build();
    }

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result);

        String raw = result.readStdout();
        if (raw.isBlank()) return FilterResult.of(result, "(empty directory)");

        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty()) return FilterResult.of(result, "(empty directory)");
        if (lines.size() <= 10 || verbose >= 2) return FilterResult.passthrough(result);

        // Large directory: compress to summary via pipeline
        FilterContext context = FilterContext.of(command, result, config, verbose, ultraCompact);
        String output = pipeline.execute(raw, context);
        return FilterResult.of(result, output);
    }
}