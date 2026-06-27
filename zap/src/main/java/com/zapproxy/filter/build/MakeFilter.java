package com.zapproxy.filter.build;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@CommandFilter("make")
@ApplicationScoped
public class MakeFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) {
            // Show only error lines on failure
            String raw = result.combined();
            List<String> errors = raw.lines()
                .filter(l -> l.startsWith("make") || l.contains("Error") || l.contains("error:"))
                .limit(15)
                .toList();
            return FilterResult.of(raw, errors.isEmpty() ? raw : String.join("\n", errors));
        }

        String raw = result.stdout();
        String lastLine = raw.lines().filter(l -> !l.isBlank()).reduce("", (a, b) -> b);
        return FilterResult.of(raw, "✓ make: " + (lastLine.isBlank() ? "done" : lastLine.trim()));
    }
}