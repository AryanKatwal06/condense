package com.condense.core;

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
    public static FilterResult passthrough(ExecutionResult result) {
        int tokens = 0;
        try {
            if (result.stdoutFile() != null) tokens += TokenCounter.count(result.stdoutFile());
            if (result.stderrFile() != null) tokens += TokenCounter.count(result.stderrFile());
        } catch (Exception e) {}
        return new FilterResult(result.combined(), tokens, tokens, false);
    }

    /**
     * Convenience factory: build a FilterResult for a successfully compressed output.
     */
    public static FilterResult of(ExecutionResult result, String filteredOutput) {
        int rawTokens = 0;
        try {
            if (result.stdoutFile() != null) rawTokens += TokenCounter.count(result.stdoutFile());
            if (result.stderrFile() != null) rawTokens += TokenCounter.count(result.stderrFile());
        } catch (Exception e) {}
        
        return new FilterResult(
            filteredOutput,
            rawTokens,
            TokenCounter.count(filteredOutput),
            true
        );
    }
}
