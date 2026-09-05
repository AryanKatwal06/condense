package com.condense.core;

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


@ApplicationScoped
public class CommandExecutor {

    private static final Logger log = Logger.getLogger(CommandExecutor.class);
    private final java.util.Set<Process> activeProcesses = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final String TIMEOUT_ENV = "CONDENSE_COMMAND_TIMEOUT_SEC";

    public static final int MAX_STREAM_BYTES = 10 * 1024 * 1024;

    /**
     * Proxy timeout. Unset or non-positive {@link #TIMEOUT_ENV} means wait until the child exits.
     */
    public static Duration resolveProxyTimeout() {
        return resolveProxyTimeout(System.getenv(TIMEOUT_ENV));
    }

    /**
     * Parse a {@link #TIMEOUT_ENV} value. Blank, non-numeric, and non-positive
     * become {@link Duration#ZERO} (wait until exit).
     */
    static Duration resolveProxyTimeout(String raw) {
        if (raw == null || raw.isBlank()) {
            return Duration.ZERO;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            return seconds <= 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            return Duration.ZERO;
        }
    }

    public ExecutionResult execute(List<String> args, Duration timeout)
            throws IOException, InterruptedException {
        return execute(args, timeout, null);
    }

    public ExecutionResult execute(List<String> args, Duration timeout, StreamListener listener)
            throws IOException, InterruptedException {

        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException("args must not be null or empty");
        }

        guardAgainstInfiniteLoop(args);

        ProcessBuilder pb = new ProcessBuilder(resolveLaunchArgs(args));
        pb.directory(Path.of("").toAbsolutePath().toFile());
        pb.redirectErrorStream(false); // MUST be false — we read stdout/stderr separately

        log.debugf("Executing: %s", String.join(" ", args));

        long startMs = System.currentTimeMillis();
        Process process = pb.start();
        process.getOutputStream().close();
        activeProcesses.add(process);

        try {
            var stdoutCapture = new StreamCapture(listener, true);
            var stderrCapture = new StreamCapture(listener, false);

            Thread stdoutThread = new Thread(() -> stdoutCapture.drain(process.getInputStream()), "condense-stdout");
            stdoutThread.start();
            Thread stderrThread = new Thread(() -> stderrCapture.drain(process.getErrorStream()), "condense-stderr");
            stderrThread.start();

            boolean finished = waitFor(process, timeout);

            if (!finished) {
                process.destroyForcibly();
                stdoutThread.interrupt();
                stderrThread.interrupt();
                long elapsed = System.currentTimeMillis() - startMs;
                log.warnf("Command '%s' timed out after %dms", String.join(" ", args), elapsed);
                try {
                    java.nio.file.Files.writeString(stderrCapture.tempFile, String.format("condense: command timed out after %ds", timeout.toSeconds()), java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.io.IOException ignored) {}
                return new ExecutionResult(
                    -1,
                    stdoutCapture.tempFile,
                    stderrCapture.tempFile,
                    elapsed
                );
            }

            if (stdoutCapture.capped || stderrCapture.capped) {
                process.destroyForcibly();
                if (listener != null) {
                    listener.onCapped();
                }
            }

            stdoutThread.join(5_000);
            stderrThread.join(5_000);

            if (stdoutCapture.error != null && !(stdoutCapture.error instanceof OutputLimitExceededException)) {
                throw new IllegalStateException(stdoutCapture.error.getMessage(), stdoutCapture.error);
            }
            if (stderrCapture.error != null && !(stderrCapture.error instanceof OutputLimitExceededException)) {
                throw new IllegalStateException(stderrCapture.error.getMessage(), stderrCapture.error);
            }

            long durationMs = System.currentTimeMillis() - startMs;
            int exitCode = process.isAlive() ? -1 : process.exitValue();

            log.debugf("Completed in %dms, exit=%d, stdout=%d bytes, stderr=%d bytes",
                durationMs, exitCode,
                stdoutCapture.size(), stderrCapture.size());

            return new ExecutionResult(
                exitCode,
                stdoutCapture.tempFile,
                stderrCapture.tempFile,
                durationMs
            );
        } finally {
            activeProcesses.remove(process);
        }
    }

    private static boolean waitFor(Process process, Duration timeout) throws InterruptedException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            process.waitFor();
            return true;
        }
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    void onStop(@jakarta.enterprise.event.Observes io.quarkus.runtime.ShutdownEvent ev) {
        for (Process p : activeProcesses) {
            if (p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroy);
                p.destroy();
                try {
                    if (!p.waitFor(2, TimeUnit.SECONDS)) {
                        p.descendants().forEach(ProcessHandle::destroyForcibly);
                        p.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                }
            }
        }
    }

    public ExecutionResult execute(List<String> args) throws IOException, InterruptedException {
        return execute(args, DEFAULT_TIMEOUT);
    }

    public ExecutionResult execute(String... args) throws IOException, InterruptedException {
        return execute(Arrays.asList(args), DEFAULT_TIMEOUT);
    }



    /**
     * On Windows, resolve {@code PATHEXT} shims ({@code pytest.cmd}, {@code npm.cmd})
     * that {@link ProcessBuilder} will not find by bare name.
     */
    private static List<String> resolveLaunchArgs(List<String> args) {
        if (!WindowsCommandResolver.isWindows()) {
            return args;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            path = System.getenv("Path");
        }
        return WindowsCommandResolver.rewrite(args, path, System.getenv("PATHEXT"));
    }

    /**
     * Guards against Condense executing itself, which would cause an infinite fork loop
     * when installed as a shell hook.
     *
     * <p>Compares the first token of {@code args} against the current process's
     * executable name. If they match (e.g., both are "condense"), throws to prevent
     * the loop.
     */
    private void guardAgainstInfiniteLoop(List<String> args) {
        String command = args.get(0).toLowerCase();
        if (!"condense".equals(command)) return; // Fast path: most commands are not "condense"

        Optional<String> currentCmd = ProcessHandle.current().info().command();
        currentCmd.ifPresent(path -> {
            String binaryName = Path.of(path).getFileName().toString().toLowerCase();
            if (binaryName.equals("condense") || binaryName.equals("condense-runner")) {
                throw new IllegalStateException(
                    "condense: refusing to execute 'condense' as a subprocess — this would loop infinitely. " +
                    "Check your hook configuration and ensure 'condense' is not in the command path."
                );
            }
        });
    }

    private static final class StreamCapture {
        private final Path tempFile;
        private final StreamListener listener;
        private final boolean stdout;
        private int bytesWritten = 0;
        private volatile Exception error = null;
        private volatile boolean capped = false;

        StreamCapture(StreamListener listener, boolean stdout) throws IOException {
            tempFile = java.nio.file.Files.createTempFile("condense-stream-", ".log");
            tempFile.toFile().deleteOnExit();
            this.listener = listener;
            this.stdout = stdout;
        }

        void drain(InputStream in) {
            try (in; java.io.OutputStream out = java.nio.file.Files.newOutputStream(tempFile)) {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    if (bytesWritten + read > MAX_STREAM_BYTES) {
                        error = new OutputLimitExceededException("condense: command output exceeded 10MB limit and was aborted");
                        capped = true;
                        break;
                    }
                    out.write(chunk, 0, read);
                    bytesWritten += read;
                    if (listener != null) {
                        if (stdout) {
                            listener.onStdout(chunk, read);
                        } else {
                            listener.onStderr(chunk, read);
                        }
                    }
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
