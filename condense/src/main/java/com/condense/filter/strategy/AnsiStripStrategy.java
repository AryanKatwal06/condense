package com.condense.filter.strategy;

import com.condense.filter.pipeline.FilterContext;
import com.condense.filter.pipeline.FilterStage;
import com.condense.filter.pipeline.StageResult;

import java.util.regex.Pattern;

public final class AnsiStripStrategy implements FilterStage {

    public static final AnsiStripStrategy INSTANCE = new AnsiStripStrategy();

    public AnsiStripStrategy() {}

    /** Matches any ANSI CSI (Control Sequence Introducer) escape. */
    private static final Pattern ANSI_PATTERN =
        Pattern.compile("\u001B\\[[0-9;]*[mGKHFJABCDsu]");

    /** Matches lines overwritten via carriage return (progress bar updates). */
    private static final Pattern CR_LINE_PATTERN =
        Pattern.compile("[^\n]*\r(?!\n)");

    @Override
    public StageResult process(String input, FilterContext context) {
        return StageResult.continueWith(strip(input));
    }

    /**
     * Removes all ANSI escape codes and carriage-return progress bar lines.
     *
     * @param text raw terminal output
     * @return clean plain-text string
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) return "";
        // Remove CR-overwritten progress lines first
        String noCr = BoundedRegex.replaceAll(
            CR_LINE_PATTERN, text, "", BoundedRegex.DOCUMENT_TIMEOUT_MS);
        return BoundedRegex.replaceAll(
            ANSI_PATTERN, noCr, "", BoundedRegex.DOCUMENT_TIMEOUT_MS);
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