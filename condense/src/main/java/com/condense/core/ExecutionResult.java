package com.condense.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The raw result of executing a shell command via {@link CommandExecutor}.
 *
 * @param exitCode     the process exit code; -1 if Condense destroyed the child or could not reap a status
 * @param stdoutFile   temp file containing captured standard output
 * @param stderrFile   temp file containing captured standard error
 * @param durationMs   wall-clock time from process start to exit, in milliseconds
 * @param termination  why wait ended; never inferred from {@code -1} alone
 */
public record ExecutionResult(
    int exitCode,
    Path stdoutFile,
    Path stderrFile,
    long durationMs,
    TerminationReason termination
) {

    public ExecutionResult {
        termination = termination == null ? TerminationReason.CHILD_EXIT : termination;
    }

    public ExecutionResult(int exitCode, Path stdoutFile, Path stderrFile, long durationMs) {
        this(exitCode, stdoutFile, stderrFile, durationMs, TerminationReason.CHILD_EXIT);
    }

    public ExecutionResult(int exitCode, String stdout, String stderr, long durationMs) {
        this(exitCode, writeStringSafe(stdout), writeStringSafe(stderr), durationMs, TerminationReason.CHILD_EXIT);
    }

    public ExecutionResult(
            int exitCode,
            String stdout,
            String stderr,
            long durationMs,
            TerminationReason termination
    ) {
        this(exitCode, writeStringSafe(stdout), writeStringSafe(stderr), durationMs, termination);
    }

    private static Path writeStringSafe(String s) {
        try {
            Path p = Files.createTempFile("condense-test", ".tmp");
            Files.writeString(p, s != null ? s : "", StandardCharsets.UTF_8);
            return p;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean succeeded() {
        return exitCode == 0;
    }

    public boolean hasStderr() {
        try {
            return stderrFile != null && Files.size(stderrFile) > 0 && !readStderr().isBlank();
        } catch (IOException e) {
            return false;
        }
    }

    public Stream<String> stdoutLines() {
        return readStdout().lines();
    }

    public Stream<String> stderrLines() {
        return readStderr().lines();
    }

    public InputStream stdoutStream() throws IOException {
        return Files.newInputStream(stdoutFile);
    }

    public InputStream stderrStream() throws IOException {
        return Files.newInputStream(stderrFile);
    }

    public String readStdout() {
        return readUtf8Replacing(stdoutFile);
    }

    public String readStderr() {
        return readUtf8Replacing(stderrFile);
    }

    /**
     * UTF-8 with replacement. Malformed bytes must not vanish into {@code ""}.
     */
    static String readUtf8Replacing(Path file) {
        if (file == null) {
            return "";
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public String combined() {
        String out = readStdout();
        String err = readStderr();
        if (out.isBlank()) return err;
        if (err.isBlank()) return out;
        return out + "\n" + err;
    }
}
