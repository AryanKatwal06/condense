package com.condense.filter.git;

import com.condense.annotation.CommandFilter;
import com.condense.core.*;
import jakarta.enterprise.context.ApplicationScoped;

@CommandFilter("git add")
@ApplicationScoped
public class GitAddFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result);

        // git add normally produces no output on success
        String raw = result.readStdout();
        if (raw.isBlank()) return FilterResult.of(result, "✓ staged");

        long fileCount = raw.lines().filter(l -> !l.isBlank()).count();
        return FilterResult.of(result, "✓ staged " + fileCount + " file(s)");
    }
}