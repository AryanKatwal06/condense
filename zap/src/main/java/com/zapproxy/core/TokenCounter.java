package com.zapproxy.core;

/**
 * Approximates GPT/Claude tokenisation for analytics purposes.
 *
 * <p>The approximation is: <strong>1 token ≈ 4 characters</strong>.
 * This matches the token savings reported in the Zap README closely enough
 * for the {@code zap gain} analytics command. A proper tokeniser (tiktoken,
 * claude-tokenizer) would add significant native-image complexity for marginal
 * accuracy gain.
 *
 * <p>This class is stateless and has no public constructor — use the static
 * {@link #count(String)} method directly.
 */
public final class TokenCounter {

    private TokenCounter() {}

    /**
     * Estimates the number of tokens in {@code text}.
     *
     * @param text the text to count; null and empty strings return 0
     * @return estimated token count, always >= 0
     */
    public static int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        // Integer ceiling division: (n + 3) / 4
        return (text.length() + 3) / 4;
    }

    /**
     * Estimates savings percentage between raw and filtered text.
     *
     * @return percentage saved, 0–100
     */
    public static int savingsPct(String raw, String filtered) {
        int rawTokens = count(raw);
        if (rawTokens == 0) return 0;
        int outTokens = count(filtered);
        return (int) (100L * (rawTokens - outTokens) / rawTokens);
    }
}
