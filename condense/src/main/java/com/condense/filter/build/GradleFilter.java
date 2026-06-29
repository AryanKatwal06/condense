package com.condense.filter.build;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("gradle"),
    @CommandFilter("./gradlew")
})
@ApplicationScoped
public class GradleFilter implements FilterStrategy {

    private static final Pattern BUILD_SUCCESSFUL = Pattern.compile("BUILD SUCCESSFUL");
    private static final Pattern BUILD_FAILED     = Pattern.compile("BUILD FAILED");
    private static final Pattern FAILURE_DETAIL   = Pattern.compile("^> ", Pattern.MULTILINE);

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        String raw = result.readStdout().isBlank() ? result.readStderr() : result.readStdout();

        if (BUILD_SUCCESSFUL.matcher(raw).find()) {
            String duration = raw.lines()
                .filter(l -> l.contains("BUILD SUCCESSFUL"))
                .findFirst().map(String::trim).orElse("BUILD SUCCESSFUL");
            return FilterResult.of(result, "✓ " + duration);
        }

        if (BUILD_FAILED.matcher(raw).find()) {
            List<String> details = raw.lines()
                .filter(l -> FAILURE_DETAIL.matcher(l).find() || l.startsWith("FAILURE:"))
                .limit(15)
                .toList();
            return FilterResult.of(result, "✗ BUILD FAILED\n" + String.join("\n", details));
        }

        return FilterResult.passthrough(result);
    }
}