package com.zapproxy.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Executes shell commands as child processes, capturing stdout and stderr
 * separately via concurrent virtual-thread readers.
 *
 * <h2>Deadlock Prevention</h2>
 * Java's {@link ProcessBuilder} will deadlock if you read stdout on the main
 * thread while the child process fills the stderr pipe buffer (or vice versa).
 * This class uses two threads — one per stream — started before
 * {@code process.waitFor()}, so both pipes drain concurrently.
 *
 * <h2>Infinite Loop Prevention</h2>
 * When Zap is installed as a shell hook, commands like "git status" are rewritten
 * to "zap git status". If Zap's internal execution of "git" somehow resolved to
 * Zap itself, it would fork infinitely. This class detects that case by comparing
 * the resolved binary name against the current process command, and throws rather
 * than looping.
 *
 * <h2>Timeout</h2>
 * All executions are bounded by a configurable timeout (default: 60 seconds).
 * Timed-out processes are forcibly destroyed and an exit code of -1 is returned.
 */
@ApplicationScoped
public class CommandExecutor {

    private static final Logger log = Logger.getLogger(CommandExecutor.class);

    /** Default maximum time to wait for a command to complete. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** Maximum bytes to buffer from a single stream. 10 MB is generous for CLI output. */
    private static final int MAX_STREAM_BYTES = 10 * 1024 * 1024;

    /**
     * Executes {@code args} as a child process and returns its captured output.
     *
     * @param args    the command and its arguments (e.g., ["git", "status", "--short"])
     * @param timeout maximum time to wait; use {@link #DEFAULT_TIMEOUT} if unsure
     * @return the execution result; never null
     * @throws IOException          if the process cannot be started (binary not found, etc.)
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws IllegalStateException if the command would create an infinite zap→zap loop
     */
    public ExecutionResult execute(List<String> args, Duration timeout)
            throws IOException, InterruptedException {

        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("args must not be null or empty");
        }

        guardAgainstInfiniteLoop(args);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(Path.of("").toAbsolutePath().toFile());
        pb.redirectErrorStream(false); // MUST be false — we read stdout/stderr separately

        log.debugf("Executing: %s", String.join(" ", args));

        long startMs = System.currentTimeMillis();
        Process process = pb.start();
        process.getOutputStream().close();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    process.destroyForcibly();
                }
            }
        }));

        // Start two threads to drain both streams concurrently.
        // This prevents the deadlock that occurs when one pipe's OS buffer fills
        // while we're blocked reading the other.
        var stdoutCapture = new StreamCapture();
        var stderrCapture = new StreamCapture();

        Thread stdoutThread = new Thread(() -> stdoutCapture.drain(process.getInputStream()), "zap-stdout");
        stdoutThread.start();
        Thread stderrThread = new Thread(() -> stderrCapture.drain(process.getErrorStream()), "zap-stderr");
        stderrThread.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);

        if (!finished) {
            process.destroyForcibly();
            stdoutThread.interrupt();
            stderrThread.interrupt();
            long elapsed = System.currentTimeMillis() - startMs;
            log.warnf("Command '%s' timed out after %dms", String.join(" ", args), elapsed);
            try {
                java.nio.file.Files.writeString(stderrCapture.tempFile, String.format("zap: command timed out after %ds", timeout.toSeconds()), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException ignored) {}
            return new ExecutionResult(
                -1,
                stdoutCapture.tempFile,
                stderrCapture.tempFile,
                elapsed
            );
        }

        // Wait for stream threads to finish draining (they will, since the process exited)
        stdoutThread.join(5_000);
        stderrThread.join(5_000);

        if (stdoutCapture.error != null) {
            if (stdoutCapture.error instanceof OutputLimitExceededException) {
                throw (OutputLimitExceededException) stdoutCapture.error;
            }
            throw new IllegalStateException(stdoutCapture.error.getMessage(), stdoutCapture.error);
        }
        if (stderrCapture.error != null) {
            if (stderrCapture.error instanceof OutputLimitExceededException) {
                throw (OutputLimitExceededException) stderrCapture.error;
            }
            throw new IllegalStateException(stderrCapture.error.getMessage(), stderrCapture.error);
        }

        long durationMs = System.currentTimeMillis() - startMs;
        int exitCode = process.exitValue();

        log.debugf("Completed in %dms, exit=%d, stdout=%d bytes, stderr=%d bytes",
            durationMs, exitCode,
            stdoutCapture.size(), stderrCapture.size());

        return new ExecutionResult(
            exitCode,
            stdoutCapture.tempFile,
            stderrCapture.tempFile,
            durationMs
        );
    }

    /**
     * Convenience overload with the default 60-second timeout.
     */
    public ExecutionResult execute(List<String> args) throws IOException, InterruptedException {
        return execute(args, DEFAULT_TIMEOUT);
    }

    /**
     * Convenience overload accepting a varargs array.
     */
    public ExecutionResult execute(String... args) throws IOException, InterruptedException {
        return execute(Arrays.asList(args), DEFAULT_TIMEOUT);
    }

    // ── private ──────────────────────────────────────────────────────────────

    /**
     * Guards against Zap executing itself, which would cause an infinite fork loop
     * when installed as a shell hook.
     *
     * <p>Compares the first token of {@code args} against the current process's
     * executable name. If they match (e.g., both are "zap"), throws to prevent
     * the loop.
     */
    private void guardAgainstInfiniteLoop(List<String> args) {
        String command = args.get(0).toLowerCase();
        if (!"zap".equals(command)) return; // Fast path: most commands are not "zap"

        Optional<String> currentCmd = ProcessHandle.current().info().command();
        currentCmd.ifPresent(path -> {
            String binaryName = Path.of(path).getFileName().toString().toLowerCase();
            if (binaryName.equals("zap") || binaryName.equals("zap-runner")) {
                throw new IllegalStateException(
                    "zap: refusing to execute 'zap' as a subprocess — this would loop infinitely. " +
                    "Check your hook configuration and ensure 'zap' is not in the command path."
                );
            }
        });
    }

    private static final class StreamCapture {
        private final Path tempFile;
        private int bytesWritten = 0;
        private volatile Exception error = null;

        StreamCapture() throws IOException {
            tempFile = java.nio.file.Files.createTempFile("zap-stream-", ".log");
            tempFile.toFile().deleteOnExit();
        }

        void drain(InputStream in) {
            try (in; java.io.OutputStream out = java.nio.file.Files.newOutputStream(tempFile)) {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    if (bytesWritten + read > MAX_STREAM_BYTES) {
                        error = new OutputLimitExceededException("zap: command output exceeded 10MB limit and was aborted");
                        break;
                    }
                    out.write(chunk, 0, read);
                    bytesWritten += read;
                }
            } catch (Exception e) {
                error = e;
            }
        }

        int size() {
            return bytesWritten;
        }
    }
}
