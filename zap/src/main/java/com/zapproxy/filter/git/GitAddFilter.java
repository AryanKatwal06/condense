package com.zapproxy.filter.git;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

@CommandFilter("git add")
@ApplicationScoped
public class GitAddFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        // git add normally produces no output on success
        String raw = result.stdout();
        if (raw.isBlank()) return FilterResult.of("", "✓ staged");

        long fileCount = raw.lines().filter(l -> !l.isBlank()).count();
        return FilterResult.of(raw, "✓ staged " + fileCount + " file(s)");
    }
}