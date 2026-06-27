package com.zapproxy.filter.node;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.AnsiStripStrategy;
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

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        String clean = AnsiStripStrategy.strip(raw);

        Matcher added = ADDED_PATTERN.matcher(clean);
        Matcher audit = AUDIT_PATTERN.matcher(clean);

        StringBuilder sb = new StringBuilder("✓ npm install");
        if (added.find()) sb.append(": ").append(added.group(1)).append(" packages");
        if (audit.find()) sb.append(" | ").append(audit.group(0));

        return FilterResult.of(raw, sb.toString());
    }
}