package com.zapproxy.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Controls when Zap saves raw command output to a tee file.
 *
 * <ul>
 *   <li>{@link #FAILURES} — save only when the command exits non-zero (default)</li>
 *   <li>{@link #ALWAYS} — always save raw output</li>
 *   <li>{@link #NEVER} — never save raw output</li>
 * </ul>
 */
public enum TeeMode {

    FAILURES,
    ALWAYS,
    NEVER;

    @JsonCreator
    public static TeeMode fromString(String value) {
        if (value == null || value.isBlank()) return FAILURES;
        return switch (value.trim().toUpperCase()) {
            case "ALWAYS" -> ALWAYS;
            case "NEVER"  -> NEVER;
            default       -> FAILURES;
        };
    }

    @JsonValue
    public String toValue() {
        return name().toLowerCase();
    }
}
