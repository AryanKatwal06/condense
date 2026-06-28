package com.zapproxy.filter.cloud;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@CommandFilters({
    @CommandFilter("docker logs"),
    @CommandFilter("docker run"),
    @CommandFilter("docker exec")
})
@ApplicationScoped
public class DockerFilter implements FilterStrategy {

    private static final int MAX_LOG_LINES = 30;

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result);

        String raw = result.readStdout().isBlank() ? result.readStderr() : result.readStdout();
        List<String> lines = raw.lines().filter(l -> !l.isBlank()).toList();

        if (lines.size() <= MAX_LOG_LINES || verbose >= 2) return FilterResult.passthrough(result);

        // Tail last MAX_LOG_LINES lines
        List<String> tail = lines.subList(lines.size() - MAX_LOG_LINES, lines.size());
        String header = "... (showing last " + MAX_LOG_LINES + " of " + lines.size() + " lines)\n";
        return FilterResult.of(result, header + String.join("\n", tail));
    }
}