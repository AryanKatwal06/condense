package com.condense.nativeimage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Locates the GraalVM native binary and runs it with isolated config/data dirs.
 * Never skips: a missing binary is a failed test, not an ignored one.
 */
public final class NativeBinarySupport {

    public static final String CONFIG_DIR_ENV = "CONDENSE_CONFIG_DIR";
    public static final String DATA_DIR_ENV = "CONDENSE_DATA_DIR";

    private static final long TIMEOUT_SECONDS = 60;

    private NativeBinarySupport() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static File requireNativeBinary() {
        String nativeImagePath = System.getProperty("native.image.path");
        if (nativeImagePath == null || nativeImagePath.isBlank()) {
            fail("native.image.path system property was not supplied — this test requires "
                + "the native binary to be built first (e.g. `mvn verify -Pnative`). "
                + "It cannot validate native-image behavior without it, so it must fail "
                + "rather than silently pass.");
        }

        File binary = new File(nativeImagePath);
        if (!binary.exists()) {
            File exeBinary = new File(nativeImagePath + ".exe");
            if (exeBinary.exists()) {
                binary = exeBinary;
            } else {
                fail("native.image.path was set to '" + nativeImagePath + "' but no binary exists "
                    + "at that path (checked both with and without a .exe suffix). The native build "
                    + "must have failed or not run before this test executed.");
            }
        }
        if (!binary.canExecute() && !isWindows()) {
            fail("Native binary at '" + binary.getAbsolutePath() + "' exists but is not executable. "
                + "Check file permissions from the native-image build step.");
        }
        return binary;
    }

    public static CliResult run(Path configDir, Path dataDir, String... args) throws Exception {
        return run(configDir, dataDir, null, args);
    }

    /**
     * Same as {@link #run(Path, Path, String...)} but records wall time around the
     * whole child process. Used by native budget and soak tests.
     */
    public static TimedCliResult timedRun(Path configDir, Path dataDir, String... args) throws Exception {
        long started = System.nanoTime();
        CliResult result = run(configDir, dataDir, args);
        return new TimedCliResult(result, System.nanoTime() - started);
    }

    public static TimedCliResult timedRun(Path configDir, Path dataDir, Path prependPathDir, String... args)
            throws Exception {
        long started = System.nanoTime();
        CliResult result = run(configDir, dataDir, prependPathDir, args);
        return new TimedCliResult(result, System.nanoTime() - started);
    }

    /**
     * Runs the native binary. When {@code prependPathDir} is non-null it is
     * prepended to {@code PATH} so a stub child command can be resolved.
     */
    public static CliResult run(Path configDir, Path dataDir, Path prependPathDir, String... args)
            throws Exception {
        return run(configDir, dataDir, prependPathDir, null, null, args);
    }

    /**
     * Runs the native binary. {@code extraEnv} values of {@code null} remove that
     * variable from the child (needed so a CI hatch test can clear inherited CI indicators).
     */
    public static CliResult run(
            Path configDir,
            Path dataDir,
            Path prependPathDir,
            Path workDir,
            Map<String, String> extraEnv,
            String... args
    ) throws Exception {
        return start(configDir, dataDir, prependPathDir, workDir, extraEnv, args).await();
    }

    /**
     * Starts the native binary so a test can peek at stdout before the child exits.
     */
    public static StartedRun start(
            Path configDir,
            Path dataDir,
            Path prependPathDir,
            String... args
    ) throws Exception {
        return start(configDir, dataDir, prependPathDir, null, null, args);
    }

    public static StartedRun start(
            Path configDir,
            Path dataDir,
            Path prependPathDir,
            Path workDir,
            Map<String, String> extraEnv,
            String... args
    ) throws Exception {
        File binary = requireNativeBinary();
        List<String> command = new ArrayList<>();
        command.add(binary.getAbsolutePath());
        command.addAll(List.of(args));

        ProcessBuilder builder = new ProcessBuilder(command);
        if (workDir != null) {
            builder.directory(workDir.toFile());
        }
        builder.environment().put(CONFIG_DIR_ENV, configDir.toAbsolutePath().toString());
        builder.environment().put(DATA_DIR_ENV, dataDir.toAbsolutePath().toString());
        if (prependPathDir != null) {
            prependPath(builder, prependPathDir);
        }
        if (extraEnv != null) {
            for (Map.Entry<String, String> entry : extraEnv.entrySet()) {
                if (entry.getValue() == null) {
                    builder.environment().remove(entry.getKey());
                } else {
                    builder.environment().put(entry.getKey(), entry.getValue());
                }
            }
        }
        builder.redirectErrorStream(false);

        Process process = builder.start();
        StreamCollector stdout = StreamCollector.start(process.getInputStream());
        StreamCollector stderr = StreamCollector.start(process.getErrorStream());
        return new StartedRun(process, stdout, stderr, command);
    }

    private static void prependPath(ProcessBuilder builder, Path extraDir) {
        String extra = extraDir.toAbsolutePath().toString();
        var env = builder.environment();
        String current = env.get("PATH");
        if (current == null) {
            current = env.get("Path");
        }
        if (current == null) {
            current = "";
        }
        String joined = extra + File.pathSeparator + current;
        env.put("PATH", joined);
        if (isWindows()) {
            env.put("Path", joined);
        }
    }

    public static String[] trivialSucceedingCommand() {
        if (isWindows()) {
            return new String[] {"cmd", "/c", "echo", "hello_native_it"};
        }
        return new String[] {"echo", "hello_native_it"};
    }

    public static String[] exitCodeCommand(int code) {
        if (isWindows()) {
            return new String[] {"cmd", "/c", "exit " + code};
        }
        return new String[] {"sh", "-c", "exit " + code};
    }

    public record CliResult(int exitCode, String stdout, String stderr) {}

    public record TimedCliResult(CliResult result, long elapsedNanos) {
        public long elapsedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        }
    }

    public static final class StartedRun {
        private final Process process;
        private final StreamCollector stdout;
        private final StreamCollector stderr;
        private final List<String> command;

        StartedRun(Process process, StreamCollector stdout, StreamCollector stderr, List<String> command) {
            this.process = process;
            this.stdout = stdout;
            this.stderr = stderr;
            this.command = command;
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public String stdoutSoFar() {
            return stdout.text();
        }

        public void writeStdin(String text) throws IOException {
            OutputStream stdin = process.getOutputStream();
            stdin.write(text.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }

        public void closeStdin() throws IOException {
            process.getOutputStream().close();
        }

        public CliResult await() throws Exception {
            try {
                boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    fail("Native binary timed out after " + TIMEOUT_SECONDS + "s: " + command);
                }
                stdout.join();
                stderr.join();
                return new CliResult(process.exitValue(), stdout.text(), stderr.text());
            } finally {
                try { process.getInputStream().close(); } catch (Throwable ignored) {}
                try { process.getErrorStream().close(); } catch (Throwable ignored) {}
                try { process.getOutputStream().close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static final class StreamCollector implements Runnable {
        private final InputStream in;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Thread thread;
        private volatile Exception failure;

        private StreamCollector(InputStream in) {
            this.in = in;
            this.thread = new Thread(this, "native-it-stream");
            this.thread.setDaemon(true);
        }

        static StreamCollector start(InputStream in) {
            StreamCollector collector = new StreamCollector(in);
            collector.thread.start();
            return collector;
        }

        @Override
        public void run() {
            try (in) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    synchronized (buffer) {
                        buffer.write(chunk, 0, read);
                    }
                }
            } catch (Exception e) {
                failure = e;
            }
        }

        void join() throws Exception {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            if (failure != null) {
                throw failure;
            }
        }

        String text() {
            synchronized (buffer) {
                return buffer.toString(StandardCharsets.UTF_8);
            }
        }
    }

    public static List<Path> findOrphanedTempFiles() {
        try {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir", "."));
            try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(tmp)) {
                return stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("condense-stream-") || name.startsWith("condense-test");
                    })
                    .toList();
            }
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public static long getOpenFileDescriptorOrHandleCount() {
        try {
            java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.UnixOperatingSystemMXBean unixBean) {
                return unixBean.getOpenFileDescriptorCount();
            }
        } catch (Throwable ignored) {}
        if (isWindows()) {
            try {
                long pid = ProcessHandle.current().pid();
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-Process -Id " + pid + ").HandleCount").start();
                try {
                    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    p.waitFor(3, TimeUnit.SECONDS);
                    return Long.parseLong(out);
                } finally {
                    try { p.getInputStream().close(); } catch (Throwable ignored) {}
                    try { p.getErrorStream().close(); } catch (Throwable ignored) {}
                    try { p.getOutputStream().close(); } catch (Throwable ignored) {}
                    p.destroy();
                }
            } catch (Throwable ignored) {}
        }
        return -1L;
    }

    public static long getCommittedMemoryBytes() {
        try {
            java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                long mem = sunBean.getCommittedVirtualMemorySize();
                if (mem > 0) {
                    return mem;
                }
            }
        } catch (Throwable ignored) {}
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
}
