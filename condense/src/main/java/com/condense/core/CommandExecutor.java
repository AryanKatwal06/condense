package com.condense.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class CommandExecutor {

    private static final Logger log = Logger.getLogger(CommandExecutor.class);
    private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();
    private final Set<Process> destroyedByShutdown = ConcurrentHashMap.newKeySet();
    private final ProcessIo io;

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final String TIMEOUT_ENV = "CONDENSE_COMMAND_TIMEOUT_SEC";

    public static final int MAX_STREAM_BYTES = 10 * 1024 * 1024;

    public static final String STDOUT_DRAIN_FAILED = "condense: stdout drain failed";
    public static final String STDERR_DRAIN_FAILED = "condense: stderr drain failed";

    private static final Set<String> SELF_BINARY_NAMES = Set.of(
        "condense",
        "condense.exe",
        "condense-runner",
        "condense-runner.exe"
    );

    public CommandExecutor() {
        this(ProcessIo.SYSTEM);
    }

    CommandExecutor(ProcessIo io) {
        this.io = io == null ? ProcessIo.SYSTEM : io;
    }

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

        checkSelfProxy(args);

        ProcessBuilder pb = new ProcessBuilder(resolveLaunchArgs(args));
        pb.directory(Path.of("").toAbsolutePath().toFile());
        pb.redirectErrorStream(false); // MUST be false — we read stdout/stderr separately

        log.debugf("Executing: %s", String.join(" ", args));

        long startMs = System.currentTimeMillis();
        Process process = io.start(pb);
        process.getOutputStream().close();
        activeProcesses.add(process);

        try {
            var stdoutCapture = new StreamCapture(io, listener, true);
            var stderrCapture = new StreamCapture(io, listener, false);

            Thread stdoutThread = new Thread(() -> stdoutCapture.drain(io.stdoutOf(process)), "condense-stdout");
            stdoutThread.start();
            Thread stderrThread = new Thread(() -> stderrCapture.drain(io.stderrOf(process)), "condense-stderr");
            stderrThread.start();

            boolean finished = waitFor(process, timeout);
            TerminationReason reason = TerminationReason.CHILD_EXIT;

            if (!finished) {
                destroyTree(process);
                reason = TerminationReason.TIMEOUT;
                long elapsed = System.currentTimeMillis() - startMs;
                log.warnf("Command '%s' timed out after %dms", String.join(" ", args), elapsed);
            }

            stdoutThread.join(5_000);
            stderrThread.join(5_000);

            if (reason == TerminationReason.CHILD_EXIT && (stdoutCapture.capped || stderrCapture.capped)) {
                destroyTree(process);
                if (listener != null) {
                    listener.onCapped();
                }
                reason = TerminationReason.OUTPUT_CAP;
            }

            appendDrainDiagnostics(stdoutCapture, stderrCapture);
            if (reason == TerminationReason.CHILD_EXIT && hasNonCapDrainError(stdoutCapture, stderrCapture)) {
                reason = TerminationReason.DRAIN_ERROR;
            }

            if (reason == TerminationReason.TIMEOUT) {
                appendDiagnostic(stderrCapture.tempFile, timeoutMessage(timeout));
            }

            long durationMs = System.currentTimeMillis() - startMs;
            int exitCode;
            if (destroyedByShutdown.contains(process)
                    && reason != TerminationReason.TIMEOUT
                    && reason != TerminationReason.OUTPUT_CAP) {
                reason = TerminationReason.DESTROYED;
                exitCode = -1;
            } else if (reason == TerminationReason.TIMEOUT) {
                exitCode = -1;
            } else if (process.isAlive()) {
                exitCode = -1;
            } else {
                exitCode = process.exitValue();
            }

            if (reason == TerminationReason.DRAIN_ERROR && process.isAlive()) {
                exitCode = -1;
            }

            log.debugf("Completed in %dms, exit=%d, stdout=%d bytes, stderr=%d bytes, termination=%s",
                durationMs, exitCode,
                stdoutCapture.size(), stderrCapture.size(), reason);

            return new ExecutionResult(
                exitCode,
                stdoutCapture.tempFile,
                stderrCapture.tempFile,
                durationMs,
                reason
            );
        } finally {
            activeProcesses.remove(process);
            destroyedByShutdown.remove(process);
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
                destroyTree(p);
                destroyedByShutdown.add(p);
            }
        }
    }

    Set<Process> activeProcessesSnapshot() {
        return Set.copyOf(activeProcesses);
    }

    static void destroyTree(Process process) {
        if (process == null) {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException ignored) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
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
     */
    static void checkSelfProxy(List<String> args) {
        if (args == null || args.isEmpty()) {
            return;
        }
        String first = args.get(0);
        if (first == null || first.isBlank()) {
            return;
        }
        if (isSelfBinaryName(Path.of(first).getFileName().toString())) {
            refuseSelfProxy();
        }
        Optional<Path> resolved = resolveOnPath(first);
        if (resolved.isPresent() && isSelfBinaryName(resolved.get().getFileName().toString())) {
            refuseSelfProxy();
        }
        Optional<String> currentCmd = ProcessHandle.current().info().command();
        if (currentCmd.isPresent() && resolved.isPresent() && sameFile(Path.of(currentCmd.get()), resolved.get())) {
            refuseSelfProxy();
        }
    }

    static boolean isSelfBinaryName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        return SELF_BINARY_NAMES.contains(fileName.toLowerCase(Locale.ROOT));
    }

    static Optional<Path> resolveOnPath(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Path asPath = Path.of(name);
        if (WindowsCommandResolver.looksLikePath(name) || asPath.isAbsolute()) {
            if (Files.exists(asPath)) {
                return Optional.of(asPath);
            }
            return Optional.empty();
        }
        if (WindowsCommandResolver.isWindows()) {
            String path = System.getenv("PATH");
            if (path == null) {
                path = System.getenv("Path");
            }
            return WindowsCommandResolver.resolve(name, path, System.getenv("PATHEXT"));
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String dir : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir.trim()).resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    private static void refuseSelfProxy() {
        throw new IllegalStateException(
            "condense: refusing to execute 'condense' as a subprocess — this would loop infinitely. " +
            "Check your hook configuration and ensure 'condense' is not in the command path."
        );
    }

    private static String timeoutMessage(Duration timeout) {
        long seconds = timeout == null ? 0 : timeout.toSeconds();
        return String.format("condense: command timed out after %ds", seconds);
    }

    static void appendDiagnostic(Path file, String message) {
        if (file == null || message == null || message.isBlank()) {
            return;
        }
        String line = message.endsWith("\n") ? message : message + "\n";
        try {
            Files.writeString(
                file,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            log.warnf("Could not append diagnostic: %s", e.getMessage());
        }
    }

    private static void appendDrainDiagnostics(StreamCapture stdoutCapture, StreamCapture stderrCapture) {
        if (isNonCapDrainError(stdoutCapture.error)) {
            log.warnf("Stdout drain failed: %s", stdoutCapture.error.getMessage());
            appendDiagnostic(stderrCapture.tempFile, STDOUT_DRAIN_FAILED);
        }
        if (isNonCapDrainError(stderrCapture.error)) {
            log.warnf("Stderr drain failed: %s", stderrCapture.error.getMessage());
            appendDiagnostic(stderrCapture.tempFile, STDERR_DRAIN_FAILED);
        }
    }

    private static boolean hasNonCapDrainError(StreamCapture stdoutCapture, StreamCapture stderrCapture) {
        return isNonCapDrainError(stdoutCapture.error) || isNonCapDrainError(stderrCapture.error);
    }

    private static boolean isNonCapDrainError(Exception error) {
        return error != null && !(error instanceof OutputLimitExceededException);
    }

    private static final class StreamCapture {
        private final Path tempFile;
        private final StreamListener listener;
        private final boolean stdout;
        private int bytesWritten = 0;
        private volatile Exception error = null;
        private volatile boolean capped = false;

        StreamCapture(ProcessIo io, StreamListener listener, boolean stdout) throws IOException {
            tempFile = io.createCaptureFile();
            this.listener = listener;
            this.stdout = stdout;
        }

        void drain(InputStream in) {
            try (in; java.io.OutputStream out = Files.newOutputStream(tempFile)) {
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
