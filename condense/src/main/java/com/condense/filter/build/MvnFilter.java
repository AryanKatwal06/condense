package com.condense.filter.build;

import com.condense.annotation.CommandFilter;
import com.condense.annotation.CommandFilters;
import com.condense.core.*;
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
                              CondenseConfig config, int verbose, boolean ultraCompact) {
        boolean isSuccess = false;
        boolean isFailure = false;
        String testLine = "";
        List<String> errors = new ArrayList<>();

        try (java.util.stream.Stream<String> stream = result.hasStderr() ? result.stderrLines() : result.stdoutLines()) {
            for (String line : (Iterable<String>) stream::iterator) {
                if (BUILD_SUCCESS.matcher(line).find()) {
                    isSuccess = true;
                }
                if (BUILD_FAILURE.matcher(line).find()) {
                    isFailure = true;
                }
                if (line.contains("Tests run:")) {
                    testLine = line;
                }
                if (ERROR_LINE.matcher(line).find() || TEST_FAIL.matcher(line).find()) {
                    errors.add(line.trim());
                }
            }
        } catch (java.io.IOException e) {
            return FilterResult.passthrough(result);
        }

        if (isSuccess) {
            return FilterResult.of(result, "✓ BUILD SUCCESS" + (testLine.isBlank() ? "" : " — " + testLine.trim()));
        }

        if (isFailure) {
            errors = errors.subList(0, Math.min(20, errors.size()));
            return FilterResult.of(result, "✗ BUILD FAILURE\n" + String.join("\n", errors));
        }

        return FilterResult.passthrough(result);
    }
}