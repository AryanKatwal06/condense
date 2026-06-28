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
        if (!result.succeeded()) return FilterResult.passthrough(result);

        Map<String, Long> byExt = new java.util.HashMap<>();
        long[] lineCount = {0};

        try (java.util.stream.Stream<String> stream = result.stdoutLines()) {
            stream.filter(l -> !l.isBlank()).forEach(l -> {
                lineCount[0]++;
                int dot = l.lastIndexOf('.');
                String ext = dot >= 0 ? l.substring(dot) : "(no extension)";
                byExt.merge(ext, 1L, Long::sum);
            });
        } catch (java.io.IOException e) {
            return FilterResult.passthrough(result);
        }

        if (lineCount[0] == 0) return FilterResult.of(result, "(no results)");
        if (lineCount[0] <= 10 || verbose >= 2) return FilterResult.passthrough(result);

        StringBuilder sb = new StringBuilder(lineCount[0] + " result(s)\n");
        byExt.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(10)
            .forEach(e -> sb.append("  ").append(e.getKey()).append(": ")
                .append(e.getValue()).append('\n'));

        return FilterResult.of(result, sb.toString().stripTrailing());
    }
}