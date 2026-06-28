package com.zapproxy.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The raw result of executing a shell command via {@link CommandExecutor}.
 *
 * @param exitCode   the process exit code; -1 if the process timed out
 * @param stdoutFile temp file containing captured standard output
 * @param stderrFile temp file containing captured standard error
 * @param durationMs wall-clock time from process start to exit, in milliseconds
 */
public record ExecutionResult(
    int exitCode,
    Path stdoutFile,
    Path stderrFile,
    long durationMs
) {

    public ExecutionResult(int exitCode, String stdout, String stderr, long durationMs) {
        this(exitCode, writeStringSafe(stdout), writeStringSafe(stderr), durationMs);
    }

    private static Path writeStringSafe(String s) {
        try {
            Path p = Files.createTempFile("zap-test", ".tmp");
            Files.writeString(p, s != null ? s : "");
            return p;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Returns true if the command exited with code 0. */
    public boolean succeeded() {
        return exitCode == 0;
    }

    /** Returns true if stderr contains any non-whitespace content. */
    public boolean hasStderr() {
        try {
            return stderrFile != null && Files.size(stderrFile) > 0 && !readStderr().isBlank();
        } catch (IOException e) {
            return false;
        }
    }

    public Stream<String> stdoutLines() throws IOException {
        return Files.lines(stdoutFile, StandardCharsets.UTF_8);
    }

    public Stream<String> stderrLines() throws IOException {
        return Files.lines(stderrFile, StandardCharsets.UTF_8);
    }

    public InputStream stdoutStream() throws IOException {
        return Files.newInputStream(stdoutFile);
    }

    public InputStream stderrStream() throws IOException {
        return Files.newInputStream(stderrFile);
    }

    public String readStdout() {
        try {
            return Files.readString(stdoutFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public String readStderr() {
        try {
            return Files.readString(stderrFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Returns the combined stdout + stderr for tee/passthrough scenarios. */
    public String combined() {
        String out = readStdout();
        String err = readStderr();
        if (out.isBlank()) return err;
        if (err.isBlank()) return out;
        return out + "\n" + err;
    }
}
