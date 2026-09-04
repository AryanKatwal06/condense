package com.condense.filter.strategy;

import com.condense.filter.pipeline.config.FilterOverrideLoader;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single choke point for regular-expression matching in the filter package.
 *
 * <p>Every matcher is wrapped in {@link TimeoutCharSequence} at
 * {@link FilterOverrideLoader#OVERRIDE_REGEX_TIMEOUT_MS} (200 ms). A timeout
 * throws {@link RegexTimeoutException}. Callers that run inside a pipeline
 * stage fail-open (the pipeline keeps the prior text). Callers at filter
 * level fail-open to passthrough. Exit codes are never altered.
 */
public final class BoundedRegex {

    public static final long TIMEOUT_MS = FilterOverrideLoader.OVERRIDE_REGEX_TIMEOUT_MS;

    /**
     * Budget for a trusted whole-document rewrite (ANSI strip, dedup suffix).
     * The 200 ms line budget is too tight for a 10 MB capture; this is still
     * bounded and still goes through {@link TimeoutCharSequence}.
     */
    public static final long DOCUMENT_TIMEOUT_MS = 5_000L;

    private BoundedRegex() {}

    public static Matcher matcher(Pattern pattern, CharSequence input) {
        return matcher(pattern, input, TIMEOUT_MS);
    }

    public static Matcher matcher(Pattern pattern, CharSequence input, long timeoutMillis) {
        Objects.requireNonNull(pattern, "pattern");
        CharSequence src = input != null ? input : "";
        long budget = timeoutMillis > 0 ? timeoutMillis : TIMEOUT_MS;
        return pattern.matcher(new TimeoutCharSequence(src, budget, pattern.pattern()));
    }

    public static boolean find(Pattern pattern, CharSequence input) {
        return matcher(pattern, input).find();
    }

    public static String replaceAll(Pattern pattern, CharSequence input, String replacement) {
        return replaceAll(pattern, input, replacement, TIMEOUT_MS);
    }

    public static String replaceAll(Pattern pattern, CharSequence input, String replacement, long timeoutMillis) {
        return matcher(pattern, input, timeoutMillis).replaceAll(replacement != null ? replacement : "");
    }
}
