package com.condense.filter.pipeline.config;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A single schema or semantic error in a declarative filter definition.
 * Parse and unknown-key errors carry Jackson line/column when the parser supplies them.
 */
@RegisterForReflection
public record DefinitionError(
    String path,
    Integer line,
    Integer column,
    String message
) {
    public DefinitionError {
        path = path != null ? path : "";
        message = message != null ? message : "";
    }

    public String format() {
        if (line != null && column != null) {
            return path + " (line " + line + ", col " + column + "): " + message;
        }
        if (line != null) {
            return path + " (line " + line + "): " + message;
        }
        if (path.isBlank()) {
            return message;
        }
        return path + ": " + message;
    }
}
