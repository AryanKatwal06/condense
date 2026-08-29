package com.condense.filter.pipeline.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Structured diagnostic result from validating a declarative filter override file.
 */
public record FilterOverrideValidationResult(
    Status status,
    Path filePath,
    List<String> errors,
    int filterCount
) {

    public enum Status {
        VALID,
        NOT_FOUND,
        SYNTAX_ERROR,
        SEMANTIC_ERROR,
        SECURITY_VIOLATION,
        ERROR
    }

    public FilterOverrideValidationResult {
        if (errors == null) {
            errors = Collections.emptyList();
        }
    }

    public boolean isValid() {
        return status == Status.VALID;
    }

    public static FilterOverrideValidationResult valid(Path filePath, int filterCount) {
        return new FilterOverrideValidationResult(Status.VALID, filePath, Collections.emptyList(), filterCount);
    }

    public static FilterOverrideValidationResult notFound(Path filePath) {
        return new FilterOverrideValidationResult(Status.NOT_FOUND, filePath, Collections.emptyList(), 0);
    }

    public static FilterOverrideValidationResult syntaxError(Path filePath, String message) {
        return new FilterOverrideValidationResult(Status.SYNTAX_ERROR, filePath, List.of(message), 0);
    }

    public static FilterOverrideValidationResult semanticError(Path filePath, List<String> errors) {
        return new FilterOverrideValidationResult(Status.SEMANTIC_ERROR, filePath, errors, 0);
    }

    public static FilterOverrideValidationResult securityViolation(Path filePath, String message) {
        return new FilterOverrideValidationResult(Status.SECURITY_VIOLATION, filePath, List.of(message), 0);
    }

    public static FilterOverrideValidationResult error(Path filePath, String message) {
        return new FilterOverrideValidationResult(Status.ERROR, filePath, List.of(message), 0);
    }
}
