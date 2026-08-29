package com.condense.filter.pipeline;

/**
 * Contract representing a single composable step in a {@link FilterPipeline}.
 *
 * <p>Filter stages must be stateless and thread-safe / reusable across multiple pipeline
 * invocations. All per-invocation state must remain local to the {@link #process} method.
 */
@FunctionalInterface
public interface FilterStage {

    /**
     * Processes input text and returns a {@link StageResult}.
     *
     * @param input   the current text passed from the preceding stage (or initial input)
     * @param context contextual metadata (command, verbosity, config, etc.)
     * @return the result containing transformed output and optional short-circuit flag
     */
    StageResult process(String input, FilterContext context);

    /**
     * Convenience method to process input text using an empty context.
     *
     * @param input the current text
     * @return the result containing transformed output
     */
    default StageResult process(String input) {
        return process(input, FilterContext.empty());
    }
}
