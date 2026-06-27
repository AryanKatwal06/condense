package com.zapproxy.core;

/**
 * The output produced by a {@link FilterStrategy} after compressing a command's
 * raw output.
 *
 * @param output      the compressed output string to print to stdout
 * @param rawTokens   estimated token count of the original command output
 * @param outTokens   estimated token count of {@code output}
 * @param wasFiltered true if any compression was applied; false if output is
 *                    identical to raw (passthrough scenario)
 */
public record FilterResult(
    String output,
    int rawTokens,
    int outTokens,
    boolean wasFiltered
) {

    /** Percentage of tokens saved, 0–100. Returns 0 if rawTokens is 0. */
    public int savingsPct() {
        if (rawTokens == 0) return 0;
        return (int) (100L * (rawTokens - outTokens) / rawTokens);
    }

    /**
     * Convenience factory: build a FilterResult for a passthrough (no filtering).
     * rawTokens == outTokens, wasFiltered == false.
     */
    public static FilterResult passthrough(String output) {
        int tokens = TokenCounter.count(output);
        return new FilterResult(output, tokens, tokens, false);
    }

    /**
     * Convenience factory: build a FilterResult for a successfully compressed output.
     */
    public static FilterResult of(String rawInput, String filteredOutput) {
        return new FilterResult(
            filteredOutput,
            TokenCounter.count(rawInput),
            TokenCounter.count(filteredOutput),
            true
        );
    }
}
