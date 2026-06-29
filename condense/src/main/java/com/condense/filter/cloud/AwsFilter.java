package com.condense.filter.cloud;

import com.condense.annotation.CommandFilter;
import com.condense.core.*;
import com.condense.filter.strategy.JsonStructureStrategy;
import jakarta.enterprise.context.ApplicationScoped;

@CommandFilter("aws")
@ApplicationScoped
public class AwsFilter implements FilterStrategy {

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result);

        String raw = result.readStdout();
        if (raw.isBlank()) return FilterResult.of(result, "✓ ok");

        // AWS CLI returns JSON — show schema skeleton
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            if (raw.length() > 500 || !verbose_mode(verbose)) {
                String skeleton = JsonStructureStrategy.skeleton(trimmed);
                return FilterResult.of(result, skeleton);
            }
        }

        return FilterResult.passthrough(result);
    }

    private boolean verbose_mode(int verbose) { return verbose >= 2; }
}