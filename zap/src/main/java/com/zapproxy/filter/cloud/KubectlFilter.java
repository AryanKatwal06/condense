package com.zapproxy.filter.cloud;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilter("kubectl")
@ApplicationScoped
public class KubectlFilter implements FilterStrategy {

    private static final Pattern NOT_RUNNING = Pattern.compile("Error|CrashLoopBackOff|OOMKilled|Pending|Terminating", Pattern.CASE_INSENSITIVE);

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();
        List<String> lines = raw.lines().toList();
        if (lines.isEmpty()) return FilterResult.passthrough(raw);

        // For 'kubectl get pods' style: compact table
        if (command.contains("get") || command.contains("describe")) {
            return compactTable(raw, lines);
        }

        // For 'kubectl logs': tail last 20 lines
        if (command.contains("logs")) {
            List<String> tail = lines.subList(Math.max(0, lines.size() - 20), lines.size());
            String note = lines.size() > 20 ? "... (showing last 20 of " + lines.size() + " lines)\n" : "";
            return FilterResult.of(raw, note + String.join("\n", tail));
        }

        return FilterResult.passthrough(raw);
    }

    private FilterResult compactTable(String raw, List<String> lines) {
        // Surface non-Running pods prominently
        List<String> unhealthy = new ArrayList<>();
        List<String> healthy   = new ArrayList<>();
        if (!lines.isEmpty()) healthy.add(lines.get(0)); // header

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (NOT_RUNNING.matcher(line).find()) {
                unhealthy.add("⚠ " + line.trim());
            } else {
                healthy.add(line);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!unhealthy.isEmpty()) {
            sb.append("UNHEALTHY PODS:\n");
            unhealthy.forEach(l -> sb.append("  ").append(l).append('\n'));
            sb.append('\n');
        }
        healthy.forEach(l -> sb.append(l).append('\n'));
        return FilterResult.of(raw, sb.toString().stripTrailing());
    }
}