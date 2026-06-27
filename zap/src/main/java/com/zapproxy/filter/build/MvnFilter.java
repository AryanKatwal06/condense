package com.zapproxy.filter.build;

import com.zapproxy.annotation.CommandFilter;
import com.zapproxy.annotation.CommandFilters;
import com.zapproxy.core.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@CommandFilters({
    @CommandFilter("mvn"),
    @CommandFilter("./mvnw")
})
@ApplicationScoped
public class MvnFilter implements FilterStrategy {

    private static final Pattern BUILD_SUCCESS = Pattern.compile("BUILD SUCCESS");
    private static final Pattern BUILD_FAILURE = Pattern.compile("BUILD FAILURE");
    private static final Pattern ERROR_LINE    = Pattern.compile("^\\[ERROR\\]");
    private static final Pattern TEST_FAIL     = Pattern.compile("Tests run:.+Failures: [1-9]");

    @Override
    public FilterResult apply(String command, ExecutionResult result,
                              ZapConfig config, int verbose, boolean ultraCompact) {
        String raw = result.stdout().isBlank() ? result.stderr() : result.stdout();
        List<String> lines = raw.lines().toList();

        if (BUILD_SUCCESS.matcher(raw).find()) {
            // Count test results if any
            String testLine = lines.stream()
                .filter(l -> l.contains("Tests run:"))
                .reduce("", (a, b) -> b);
            return FilterResult.of(raw,
                "✓ BUILD SUCCESS" + (testLine.isBlank() ? "" : " — " + testLine.trim()));
        }

        if (BUILD_FAILURE.matcher(raw).find()) {
            List<String> errors = new ArrayList<>();
            for (String line : lines) {
                if (ERROR_LINE.matcher(line).find()) errors.add(line.trim());
                if (TEST_FAIL.matcher(line).find()) errors.add(line.trim());
            }
            errors = errors.subList(0, Math.min(20, errors.size()));
            return FilterResult.of(raw,
                "✗ BUILD FAILURE\n" + String.join("\n", errors));
        }

        return FilterResult.passthrough(raw);
    }
}