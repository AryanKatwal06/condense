package com.condense.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Accessibility and presentation policy for Condense.
 *
 * <p>Handles:
 * <ul>
 *   <li>{@code NO_COLOR} / {@code CLICOLOR} / {@code CLICOLOR_FORCE} environment variables</li>
 *   <li>Explicit {@code --plain} / {@code --ascii} mode</li>
 *   <li>Non-UTF-8 charset fallback to ASCII</li>
 *   <li>Color-independent textual severity markers ([OK], [ERROR], etc.)</li>
 *   <li>Terminal width clamping (0 = unconstrained, min = 40, default = 80)</li>
 * </ul>
 */
public final class AccessibilityPolicy {

    private static final Pattern ANSI_PATTERN =
        Pattern.compile("\\u001B\\[[0-9;]*[mGKHFJABCDsu]");

    private final boolean plainFlag;
    private final boolean colorEnabled;
    private final boolean asciiOnly;
    private final int terminalWidth;

    public AccessibilityPolicy(boolean plainFlag, Map<String, String> env, Charset charset) {
        this.plainFlag = plainFlag;

        String noColor = env != null ? env.get("NO_COLOR") : System.getenv("NO_COLOR");
        String cliColor = env != null ? env.get("CLICOLOR") : System.getenv("CLICOLOR");
        String cliColorForce = env != null ? env.get("CLICOLOR_FORCE") : System.getenv("CLICOLOR_FORCE");
        String columns = env != null ? env.get("COLUMNS") : System.getenv("COLUMNS");

        // Explicit plain/ascii flag overrides all environment settings
        if (plainFlag) {
            this.colorEnabled = false;
        } else if (cliColorForce != null && !cliColorForce.isBlank() && !"0".equals(cliColorForce.trim())) {
            this.colorEnabled = true;
        } else if (noColor != null) {
            this.colorEnabled = false;
        } else if ("0".equals(cliColor != null ? cliColor.trim() : null)) {
            this.colorEnabled = false;
        } else {
            this.colorEnabled = true;
        }

        Charset cs = charset != null ? charset : Charset.defaultCharset();
        boolean utf8 = StandardCharsets.UTF_8.equals(cs);
        this.asciiOnly = plainFlag || !utf8;

        int parsedWidth = 80;
        if (columns != null) {
            try {
                parsedWidth = Integer.parseInt(columns.trim());
            } catch (NumberFormatException ignored) {
                parsedWidth = 80;
            }
        }
        this.terminalWidth = clampWidth(parsedWidth);
    }

    public static int clampWidth(int width) {
        if (width <= 0) {
            return 0;
        }
        return Math.max(40, width);
    }

    public static AccessibilityPolicy defaults() {
        return new AccessibilityPolicy(false, System.getenv(), Charset.defaultCharset());
    }

    public static AccessibilityPolicy of(boolean plainFlag) {
        return new AccessibilityPolicy(plainFlag, System.getenv(), Charset.defaultCharset());
    }

    public static AccessibilityPolicy of(boolean plainFlag, Map<String, String> env, Charset charset) {
        return new AccessibilityPolicy(plainFlag, env, charset);
    }

    public boolean isPlain() {
        return plainFlag || asciiOnly || !colorEnabled;
    }

    public boolean isColorEnabled() {
        return colorEnabled;
    }

    public boolean isAsciiOnly() {
        return asciiOnly;
    }

    public int terminalWidth() {
        return terminalWidth;
    }

    public String format(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String formatted = text;
        if (!colorEnabled) {
            formatted = stripAnsi(formatted);
        }
        if (asciiOnly) {
            formatted = toAscii(formatted);
        }
        return formatted;
    }

    public static String stripAnsi(String text) {
        if (text == null) return "";
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    public static String toAscii(String text) {
        if (text == null) return "";
        return text
            .replace("✓", "[OK]")
            .replace("✗", "[ERROR]")
            .replace("○", "[NONE]")
            .replace("─", "-")
            .replace("═", "=")
            .replace("↑", "^")
            .replace("█", "#")
            .replace("▌", "|")
            .replace("░", "-");
    }
}
