package com.condense.core;

/**
 * Contract for all command output filter implementations.
 *
 * <p>Each implementation handles one or more shell commands (e.g., "git status",
 * "cargo test") and compresses their raw output into a dense summary for AI context
 * windows. Implementations are CDI beans annotated with
 * {@link com.condense.annotation.CommandFilter}.
 *
 * <p>Implementations must be stateless — a single instance is used for all
 * invocations. All state must be local to the {@link #apply} method.
 */
public interface FilterStrategy {

    /**
     * Applies this filter to the raw command output.
     *
     * <p>Implementations must obey these contracts:
     * <ul>
     *   <li>If {@code result.exitCode()} is non-zero and the command genuinely
     *       failed, return the stderr verbatim (possibly via
     *       {@link FilterResult#passthrough}) unless the filter specifically
     *       handles failure output (e.g., test runners where failures ARE the
     *       interesting output).</li>
     *   <li>Never throw an exception — catch all errors, log a warning, and return
     *       {@link FilterResult#passthrough} as a safe fallback.</li>
     *   <li>Never modify the exit code — the caller propagates the original exit
     *       code from {@link ExecutionResult#exitCode()} to the OS.</li>
     * </ul>
     *
     * @param command      the full command string (e.g., "git status"),
     *                     used for logging and tee file naming
     * @param result       the raw output from {@link CommandExecutor}
     * @param config       the user's CondenseConfig (for exclude lists, tee settings, etc.)
     * @param verbose      verbosity level: 0 = compact, 1 = normal, 2 = verbose,
     *                     3 = maximum verbosity (show everything)
     * @param ultraCompact if true, produce the absolute minimum output:
     *                     single-line ASCII icon format
     * @return a {@link FilterResult} with compressed output and token metadata;
     *         never null
     */
    FilterResult apply(
        String command,
        ExecutionResult result,
        CondenseConfig config,
        int verbose,
        boolean ultraCompact
    );
}
