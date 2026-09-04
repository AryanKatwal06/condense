package com.condense.core;

import java.nio.file.Path;

/**
 * Static facade over {@link Utf8WeightedTokenEstimator} so call sites such as
 * {@link FilterResult} do not need to know which estimator is active.
 *
 * <p>Counts are estimates. The algorithm, the reference tokenizer, and the
 * published p95 error bound are documented in {@code docs/token-estimator.md}.
 */
public final class TokenCounter {

    private static final TokenEstimator ESTIMATOR = Utf8WeightedTokenEstimator.INSTANCE;

    private TokenCounter() {}

    public static TokenEstimator estimator() {
        return ESTIMATOR;
    }

    /**
     * Estimates the number of tokens in {@code text}.
     *
     * @param text the text to count; null and empty strings return 0
     * @return estimated token count, always &gt;= 0
     */
    public static int count(String text) {
        return ESTIMATOR.count(text);
    }

    /**
     * Estimates tokens in a UTF-8 file using the same function as {@link #count(String)}.
     *
     * @param file null or unreadable returns 0
     * @return estimated token count, always &gt;= 0
     */
    public static int count(Path file) {
        return ESTIMATOR.count(file);
    }

    /**
     * Estimates savings percentage between raw and filtered text.
     *
     * @return percentage saved, 0–100
     */
    public static int savingsPct(String raw, String filtered) {
        int rawTokens = count(raw);
        if (rawTokens == 0) {
            return 0;
        }
        int outTokens = count(filtered);
        return (int) (100L * (rawTokens - outTokens) / rawTokens);
    }
}
