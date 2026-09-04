package com.condense.nativeimage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        File binary = requireNativeBinary();
        List<String> command = new ArrayList<>();
        command.add(binary.getAbsolutePath());
        command.addAll(List.of(args));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put(CONFIG_DIR_ENV, configDir.toAbsolutePath().toString());
        builder.environment().put(DATA_DIR_ENV, dataDir.toAbsolutePath().toString());
        builder.redirectErrorStream(false);

        Process process = builder.start();
        StreamCollector stdout = StreamCollector.start(process.getInputStream());
        StreamCollector stderr = StreamCollector.start(process.getErrorStream());

        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("Native binary timed out after " + TIMEOUT_SECONDS + "s: " + command);
        }

        stdout.join();
        stderr.join();
        return new CliResult(process.exitValue(), stdout.text(), stderr.text());
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
                in.transferTo(buffer);
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
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
