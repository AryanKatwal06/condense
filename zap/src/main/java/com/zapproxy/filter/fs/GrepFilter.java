package com.zapproxy.filter.fs;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@CommandFilters({
    @CommandFilter("grep"),
    @CommandFilter("rg")
})
@ApplicationScoped
public class GrepFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (result.exitCode() == 1) {
            // grep exits 1 when no matches — not an error
            return FilterResult.of(result.stdout(), "(no matches)");
        }
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();
        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();

        if (lines.isEmpty()) return FilterResult.of(raw, "(no matches)");
        if (lines.size() <= 10 || verbose >= 2) return FilterResult.passthrough(raw);

        // Count matches per file
        Map<String, Integer> byFile = new LinkedHashMap<>();
        for (String line : lines) {
            int colon = line.indexOf(':');
            String file = colon > 0 ? line.substring(0, colon) : "(stdin)";
            byFile.merge(file, 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder(lines.size() + " match(es) in ")
            .append(byFile.size()).append(" file(s)\n");
        byFile.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> sb.append("  ").append(e.getKey()).append(": ")
                .append(e.getValue()).append('\n'));

        return FilterResult.of(raw, sb.toString().stripTrailing());
    }
}