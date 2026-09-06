package com.condense.filter.pipeline.config;

import com.condense.filter.strategy.BoundedRegex;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Shared declarative-stage validation helpers. Message text must stay stable
 * so {@link LegacyStageFactory} parity keeps passing.
 */
public final class StageValidation {

    private StageValidation() {}

    public static void validateRegex(
        String location,
        String field,
        String pattern,
        boolean requireCapture,
        List<String> errors
    ) {
        if (pattern == null || pattern.isBlank()) {
            if (requireCapture) {
                return;
            }
            errors.add(location + ": " + field + " must not be empty");
            return;
        }
        if (pattern.length() > StageFactory.MAX_PATTERN_LENGTH) {
            errors.add(location + ": " + field + " regex exceeds maximum allowed length of "
                + StageFactory.MAX_PATTERN_LENGTH + " characters");
            return;
        }
        try {
            Pattern compiled = Pattern.compile(pattern);
            if (requireCapture && BoundedRegex.matcher(compiled, "").groupCount() < 1) {
                errors.add(location + ": " + field + " regex must contain at least one capture group (e.g. '(.*)')");
            }
        } catch (PatternSyntaxException e) {
            errors.add(location + ": Invalid regex in " + field + ": " + e.getMessage());
        }
    }
}
