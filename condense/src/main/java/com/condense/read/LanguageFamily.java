package com.condense.read;

import java.util.Locale;

/**
 * Hardcoded scanner families. A language TOML names one of these strings;
 * it cannot name a Java class.
 */
public enum LanguageFamily {
    C_LIKE,
    HASH,
    XML,
    CSS,
    SQL,
    DATA,
    MARKDOWN,
    POWERSHELL;

    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static LanguageFamily parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("family is required");
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (key) {
            case "c_like", "clike" -> C_LIKE;
            case "hash" -> HASH;
            case "xml", "html" -> XML;
            case "css" -> CSS;
            case "sql" -> SQL;
            case "data" -> DATA;
            case "markdown", "md" -> MARKDOWN;
            case "powershell" -> POWERSHELL;
            default -> throw new IllegalArgumentException("unknown language family '" + raw + "'");
        };
    }
}
