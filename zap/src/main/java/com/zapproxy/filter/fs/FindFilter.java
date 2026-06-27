package com.zapproxy.filter.fs;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@CommandFilter("find")
@ApplicationScoped
public class FindFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();
        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();

        if (lines.isEmpty()) return FilterResult.of(raw, "(no results)");
        if (lines.size() <= 10 || verbose >= 2) return FilterResult.passthrough(raw);

        // Group by extension
        Map<String, Long> byExt = lines.stream().collect(Collectors.groupingBy(
            l -> {
                int dot = l.lastIndexOf('.');
                return dot >= 0 ? l.substring(dot) : "(no extension)";
            }, Collectors.counting()
        ));

        StringBuilder sb = new StringBuilder(lines.size() + " result(s)\n");
        byExt.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> sb.append("  ").append(e.getKey()).append(": ")
                .append(e.getValue()).append('\n'));

        return FilterResult.of(raw, sb.toString().stripTrailing());
    }
}