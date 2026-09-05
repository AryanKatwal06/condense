package com.condense.read;

import java.util.Locale;

/**
 * Compression level for {@code condense read}.
 */
public enum ReadLevel {
    VERBATIM,
    COMMENTS,
    OUTLINE;

    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ReadLevel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return COMMENTS;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "verbatim" -> VERBATIM;
            case "comments", "comment", "comment-strip" -> COMMENTS;
            case "outline", "structural" -> OUTLINE;
            default -> throw new IllegalArgumentException(
                "unknown read level '" + raw + "' (use verbatim, comments, or outline)");
        };
    }
}
