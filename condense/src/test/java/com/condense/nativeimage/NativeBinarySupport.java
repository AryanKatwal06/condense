package com.condense.nativeimage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
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

        public CliResult await() throws Exception {
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                fail("Native binary timed out after " + TIMEOUT_SECONDS + "s: " + command);
            }
            stdout.join();
            stderr.join();
            return new CliResult(process.exitValue(), stdout.text(), stderr.text());
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
            try {
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
}
