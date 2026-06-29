package com.condense.filter.fs;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;

@CommandFilters({
    @CommandFilter("grep"),
    @CommandFilter("rg")
})
@ApplicationScoped
public class GrepFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        if (result.exitCode() == 1) {
            // grep exits 1 when no matches — not an error
            return FilterResult.of(result, "(no matches)");
        }
        if (!result.succeeded()) return FilterResult.passthrough(result);

        Map<String, Integer> byFile = new LinkedHashMap<>();
        int[] lineCount = {0};

        try (java.util.stream.Stream<String> stream = result.stdoutLines()) {
            stream.filter(l -> !l.isBlank()).forEach(line -> {
                lineCount[0]++;
                int colon = line.indexOf(':');
                String file = colon > 0 ? line.substring(0, colon) : "(stdin)";
                byFile.merge(file, 1, Integer::sum);
            });
        } catch (java.io.IOException e) {
            return FilterResult.passthrough(result);
        }

        if (lineCount[0] == 0) return FilterResult.of(result, "(no matches)");
        if (lineCount[0] <= 10 || verbose >= 2) return FilterResult.passthrough(result);

        StringBuilder sb = new StringBuilder(lineCount[0] + " match(es) in ")
            .append(byFile.size()).append(" file(s)\n");
        byFile.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> sb.append("  ").append(e.getKey()).append(": ")
                .append(e.getValue()).append('\n'));

        return FilterResult.of(result, sb.toString().stripTrailing());
    }
}