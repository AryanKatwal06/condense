package com.zapproxy.filter.strategy;

import java.util.regex.Pattern;

/**
 * Strips ANSI escape sequences and carriage-return progress lines from text.
 *
 * <p>Used by install/download filters (npm install, pip install, cargo install,
 * docker build) that produce animated progress output not suitable for AI context.
 */
public final class AnsiStripStrategy {

    private AnsiStripStrategy() {}

    /** Matches any ANSI CSI (Control Sequence Introducer) escape. */
    private static final Pattern ANSI_PATTERN =
        Pattern.compile("\u001B\\[[0-9;]*[mGKHFJABCDsu]");

    /** Matches lines overwritten via carriage return (progress bar updates). */
    private static final Pattern CR_LINE_PATTERN =
        Pattern.compile("[^\n]*\r(?!\n)");

    /**
     * Removes all ANSI escape codes and carriage-return progress bar lines.
     *
     * @param text raw terminal output
     * @return clean plain-text string
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) return "";
        // Remove CR-overwritten progress lines first
        String noCr = CR_LINE_PATTERN.matcher(text).replaceAll("");
        // Then strip ANSI codes
        return ANSI_PATTERN.matcher(noCr).replaceAll("");
    }

    /**
     * Strips ANSI and then returns only the last non-blank line.
     * Useful for install commands where only the final status line matters.
     */
    public static String lastMeaningfulLine(String text) {
        String clean = strip(text);
        return clean.lines()
            .filter(l -> !l.isBlank())
            .reduce("", (a, b) -> b); // keep last
    }
}