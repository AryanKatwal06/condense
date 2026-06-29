package com.condense.filter.fs;

import com.condense.annotation.CommandFilter;
import com.condense.core.*;
import com.condense.filter.strategy.TreeCompressionStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@CommandFilter("ls")
@ApplicationScoped
public class LsFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result);

        List<String> lines;
        try (java.util.stream.Stream<String> stream = result.stdoutLines()) {
            lines = stream.filter(l -> !l.isBlank()).toList();
        } catch (java.io.IOException e) {
            return FilterResult.passthrough(result);
        }

        if (lines.isEmpty()) return FilterResult.of(result, "(empty directory)");
        if (lines.size() <= 10 || verbose >= 2) return FilterResult.passthrough(result);

        // Large directory: compress to summary
        String tree = TreeCompressionStrategy.compress(lines);
        if (tree.isBlank()) return FilterResult.of(result, lines.size() + " items");

        return FilterResult.of(result, tree);
    }
}