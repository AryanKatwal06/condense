package com.zapproxy.filter.cloud;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@CommandFilter("docker ps")
@ApplicationScoped
public class DockerPsFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();
        List<String> lines = raw.lines().toList();
        if (lines.size() <= 1) return FilterResult.of(raw, "(no containers running)");

        // Parse docker ps tabular output: CONTAINER ID | IMAGE | STATUS | NAMES
        List<String> compact = new ArrayList<>();
        compact.add("ID       IMAGE                STATUS    NAME");
        compact.add("─".repeat(55));

        for (int i = 1; i < lines.size(); i++) {   // skip header
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String[] cols = line.split("\\s{2,}");
            if (cols.length < 7) {
                compact.add(line.trim());
                continue;
            }
            String id     = cols[0].length() > 8 ? cols[0].substring(0, 8) : cols[0];
            String image  = cols[1].length() > 20 ? cols[1].substring(0, 19) + "…" : cols[1];
            String status = cols[4].length() > 10 ? cols[4].substring(0, 10) : cols[4];
            String name   = cols[cols.length - 1];
            compact.add(String.format("%-8s %-20s %-10s %s", id, image, status, name));
        }

        return FilterResult.of(raw, String.join("\n", compact));
    }
}