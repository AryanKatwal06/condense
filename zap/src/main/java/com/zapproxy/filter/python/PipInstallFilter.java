package com.zapproxy.filter.python;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.AnsiStripStrategy;
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
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        String clean = AnsiStripStrategy.strip(raw);

        List<String> installed = clean.lines()
            .filter(l -> l.startsWith("Successfully installed"))
            .toList();

        if (installed.isEmpty()) {
            String lastLine = AnsiStripStrategy.lastMeaningfulLine(clean);
            return FilterResult.of(raw, lastLine.isBlank() ? "✓ pip install" : lastLine);
        }

        return FilterResult.of(raw, installed.get(installed.size() - 1).trim());
    }
}