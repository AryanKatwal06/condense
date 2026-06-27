package com.zapproxy.filter.fs;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.TreeCompressionStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@CommandFilter("ls")
@ApplicationScoped
public class LsFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();
        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();

        if (lines.isEmpty()) return FilterResult.of(raw, "(empty directory)");
        if (lines.size() <= 10 || verbose >= 2) return FilterResult.passthrough(raw);

        // Large directory: compress to summary
        String tree = TreeCompressionStrategy.compress(lines);
        if (tree.isBlank()) return FilterResult.of(raw, lines.size() + " items");

        return FilterResult.of(raw, tree);
    }
}