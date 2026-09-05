package com.condense.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Locale;

/**
 * Why {@link CommandExecutor} stopped waiting on a child. Never inferred from
 * exit {@code -1} alone — timeout and cap can both yield {@code -1}.
 */
@RegisterForReflection
public enum TerminationReason {
    CHILD_EXIT,
    TIMEOUT,
    OUTPUT_CAP,
    DRAIN_ERROR,
    DESTROYED;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static TerminationReason fromWire(String value) {
        if (value == null || value.isBlank()) {
            return CHILD_EXIT;
        }
        String key = value.trim().toLowerCase(Locale.ROOT);
        for (TerminationReason reason : values()) {
            if (reason.wire().equals(key)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("unknown termination reason: " + value);
    }
}
