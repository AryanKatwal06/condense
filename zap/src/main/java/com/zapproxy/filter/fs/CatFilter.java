package com.zapproxy.filter.fs;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import com.zapproxy.filter.strategy.JsonStructureStrategy;
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
                              ZapConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result.combined());

        String raw = result.stdout();

        // Small output — pass through as-is
        if (raw.length() <= CHAR_LIMIT_BEFORE_COMPRESS || verbose >= 2) {
            return FilterResult.passthrough(raw);
        }

        String trimmed = raw.trim();

        // JSON content — show schema skeleton
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                String skeleton = JsonStructureStrategy.skeleton(trimmed);
                return FilterResult.of(raw, skeleton);
            } catch (Exception e) {
                log.debugf("CatFilter JSON parse failed: %s", e.getMessage());
            }
        }

        // Large text — show first + last section
        String[] lines = raw.split("\n");
        if (lines.length > 40) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20; i++) sb.append(lines[i]).append('\n');
            sb.append("... (").append(lines.length - 40).append(" lines omitted) ...\n");
            for (int i = lines.length - 20; i < lines.length; i++) sb.append(lines[i]).append('\n');
            return FilterResult.of(raw, sb.toString().stripTrailing());
        }

        return FilterResult.passthrough(raw);
    }
}