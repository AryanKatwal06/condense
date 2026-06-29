package com.condense.filter.python;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.*;
import com.condense.filter.strategy.AnsiStripStrategy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@CommandFilters({
    @CommandFilter("pip install"),
    @CommandFilter("pip3 install")
})
@ApplicationScoped
public class PipInstallFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result);

        String raw = result.readStdout().isBlank() ? result.readStderr() : result.readStdout();
        String clean = AnsiStripStrategy.strip(raw);

        List<String> installed = clean.lines()
            .filter(l -> l.startsWith("Successfully installed"))
            .toList();

        if (installed.isEmpty()) {
            String lastLine = AnsiStripStrategy.lastMeaningfulLine(clean);
            return FilterResult.of(result, lastLine.isBlank() ? "✓ pip install" : lastLine);
        }

        return FilterResult.of(result, installed.get(installed.size() - 1).trim());
    }
}