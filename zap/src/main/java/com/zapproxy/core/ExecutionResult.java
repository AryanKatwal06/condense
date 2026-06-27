package com.zapproxy.core;

/**
 * The raw result of executing a shell command via {@link CommandExecutor}.
 *
 * @param exitCode   the process exit code; -1 if the process timed out
 * @param stdout     captured standard output, never null (empty string if none)
 * @param stderr     captured standard error, never null (empty string if none)
 * @param durationMs wall-clock time from process start to exit, in milliseconds
 */
public record ExecutionResult(
    int exitCode,
    String stdout,
    String stderr,
    long durationMs
) {

    /** Returns true if the command exited with code 0. */
    public boolean succeeded() {
        return exitCode == 0;
    }

    /** Returns true if stderr contains any non-whitespace content. */
    public boolean hasStderr() {
        return stderr != null && !stderr.isBlank();
    }

    /** Returns the combined stdout + stderr for tee/passthrough scenarios. */
    public String combined() {
        if (stdout.isBlank()) return stderr;
        if (stderr.isBlank()) return stdout;
        return stdout + "\n" + stderr;
    }
}
