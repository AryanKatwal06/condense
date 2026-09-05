package com.condense.read;

import java.util.Locale;

/**
 * Family-adjacent raw-string syntax that cannot be expressed as a single delimiter.
 */
public enum RawStringStyle {
    NONE,
    RUST,
    CPP,
    PYTHON,
    JAVA_TEXT,
    JS_TEMPLATE,
    GO_RAW;

    public static RawStringStyle parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (key) {
            case "none" -> NONE;
            case "rust" -> RUST;
            case "cpp", "cplusplus" -> CPP;
            case "python" -> PYTHON;
            case "java_text", "java" -> JAVA_TEXT;
            case "js_template", "javascript", "template" -> JS_TEMPLATE;
            case "go_raw", "go" -> GO_RAW;
            default -> throw new IllegalArgumentException("unknown raw_strings style '" + raw + "'");
        };
    }
}
