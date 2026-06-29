package com.condense.filter.fs;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.*;
import com.condense.filter.strategy.JsonStructureStrategy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@CommandFilters({
    @CommandFilter("cat"),
    @CommandFilter("read")
})
@ApplicationScoped
public class CatFilter implements FilterStrategy {

    private static final Logger log = Logger.getLogger(CatFilter.class);
    private static final int CHAR_LIMIT_BEFORE_COMPRESS = 2000;

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result);

        long size = 0;
        try {
            size = java.nio.file.Files.size(result.stdoutFile());
        } catch (java.io.IOException e) {}

        // Small output — pass through as-is
        if (size <= CHAR_LIMIT_BEFORE_COMPRESS || verbose >= 2) {
            return FilterResult.passthrough(result);
        }

        java.util.List<String> first20 = new java.util.ArrayList<>();
        java.util.List<String> last20 = new java.util.ArrayList<>();
        int[] count = {0};

        try (java.util.stream.Stream<String> stream = result.stdoutLines()) {
            stream.forEach(line -> {
                if (count[0] < 20) {
                    first20.add(line);
                }
                if (last20.size() >= 20) {
                    last20.remove(0);
                }
                last20.add(line);
                count[0]++;
            });
        } catch (java.io.IOException e) {
            return FilterResult.passthrough(result);
        }

        if (count[0] > 0) {
            String trimmed = first20.get(0).trim();
            // JSON content — show schema skeleton
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    String raw = result.readStdout();
                    String skeleton = JsonStructureStrategy.skeleton(raw.trim());
                    return FilterResult.of(result, skeleton);
                } catch (Exception e) {
                    log.debugf("CatFilter JSON parse failed: %s", e.getMessage());
                }
            }
        }

        // Large text — show first + last section
        if (count[0] > 40) {
            StringBuilder sb = new StringBuilder();
            for (String l : first20) sb.append(l).append('\n');
            sb.append("... (").append(count[0] - 40).append(" lines omitted) ...\n");
            for (String l : last20) sb.append(l).append('\n');
            return FilterResult.of(result, sb.toString().stripTrailing());
        }

        return FilterResult.passthrough(result);
    }
}