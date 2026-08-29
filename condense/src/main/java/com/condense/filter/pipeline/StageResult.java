package com.condense.filter.pipeline;

/**
 * The result produced by a {@link FilterStage}.
 *
 * @param output       the transformed text output
 * @param shortCircuit whether the pipeline should immediately terminate and return this output
 */
public record StageResult(
    String output,
    boolean shortCircuit
) {
    public StageResult {
        if (output == null) {
            output = "";
        }
    }

    /**
     * Creates a result that continues pipeline execution with the given output.
     */
    public static StageResult continueWith(String output) {
        return new StageResult(output, false);
    }

    /**
     * Creates a result that halts pipeline execution early and returns the given output.
     */
    public static StageResult stopWith(String output) {
        return new StageResult(output, true);
    }

    /**
     * Convenience factory defaulting to continuing pipeline execution.
     */
    public static StageResult of(String output) {
        return continueWith(output);
    }
}
