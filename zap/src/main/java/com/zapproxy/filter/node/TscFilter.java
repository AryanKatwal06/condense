package com.zapproxy.filter.node;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandFilter("tsc")
@ApplicationScoped
public class TscFilter implements FilterStrategy {

    // "src/foo.ts(12,3): error TS2345: ..."
    private static final Pattern FILE_PATTERN =
        Pattern.compile("^(\\S+\\.ts)\\(");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        String raw = result.readStdout().isBlank() ? result.readStderr() : result.readStdout();
        List<String> lines = raw.lines().toList();

        Map<String, Integer> byFile = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher m = FILE_PATTERN.matcher(line);
            if (m.find()) byFile.merge(m.group(1), 1, Integer::sum);
        }

        if (byFile.isEmpty() && result.succeeded()) return FilterResult.of(result, "✓ no type errors");

        long total = byFile.values().stream().mapToLong(Integer::longValue).sum();
        StringBuilder sb = new StringBuilder("tsc: ").append(total).append(" error(s)\n");
        byFile.forEach((f, c) -> sb.append("  ").append(f).append(": ").append(c).append('\n'));

        return FilterResult.of(result, sb.toString().stripTrailing());
    }
}