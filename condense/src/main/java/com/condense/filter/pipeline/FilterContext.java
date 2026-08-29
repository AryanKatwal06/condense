package com.condense.filter.pipeline;

import com.condense.core.CondenseConfig;
import com.condense.core.ExecutionResult;

/**
 * Contextual metadata provided to {@link FilterStage} instances during pipeline execution.
 *
 * @param command      the command being filtered (e.g. "npm install")
 * @param result       the raw ExecutionResult, if available (may be null in standalone stage tests)
 * @param config       the Condense configuration
 * @param verbose      verbosity level (0=compact, 1=normal, 2=verbose, 3=maximum)
 * @param ultraCompact whether ultra-compact mode is enabled
 */
public record FilterContext(
    String command,
    ExecutionResult result,
    CondenseConfig config,
    int verbose,
    boolean ultraCompact
) {
    public static FilterContext empty() {
        return new FilterContext("", null, CondenseConfig.defaults(), 0, false);
    }

    public static FilterContext of(String command, ExecutionResult result, CondenseConfig config, int verbose, boolean ultraCompact) {
        return new FilterContext(
            command != null ? command : "",
            result,
            config != null ? config : CondenseConfig.defaults(),
            verbose,
            ultraCompact
        );
    }
}
