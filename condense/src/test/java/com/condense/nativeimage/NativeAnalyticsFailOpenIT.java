package com.condense.nativeimage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analytics must not change the proxied exit code. After a successful write the
 * database is replaced with garbage so the next process cannot open SQLite.
 * Never skips; this is cross-platform (no POSIX-only chmod, no runner home dir).
 */
class NativeAnalyticsFailOpenIT {

    @TempDir
    Path tempDir;

    @Test
    void proxiedCommandStillExitsZeroWhenDatabaseIsCorrupt() throws Exception {
        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);

        NativeBinarySupport.CliResult first = NativeBinarySupport.run(
            configDir, dataDir, NativeBinarySupport.trivialSucceedingCommand());
        assertThat(first.exitCode())
            .as("seed run stdout=%s stderr=%s", first.stdout(), first.stderr())
            .isZero();

        corruptDatabase(dataDir);

        NativeBinarySupport.CliResult second = NativeBinarySupport.run(
            configDir, dataDir, NativeBinarySupport.trivialSucceedingCommand());
        assertThat(second.exitCode())
            .as("fail-open run must keep the child exit code: stdout=%s stderr=%s",
                second.stdout(), second.stderr())
            .isZero();
    }

    @Test
    void gainReportsAnalyticsUnavailableWhenDatabaseIsCorrupt() throws Exception {
        Path configDir = tempDir.resolve("gain-config");
        Path dataDir = tempDir.resolve("gain-data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);

        NativeBinarySupport.CliResult first = NativeBinarySupport.run(
            configDir, dataDir, NativeBinarySupport.trivialSucceedingCommand());
        assertThat(first.exitCode()).isZero();

        corruptDatabase(dataDir);

        NativeBinarySupport.CliResult gain = NativeBinarySupport.run(
            configDir, dataDir, "gain", "--format", "json");
        assertThat(gain.exitCode())
            .as("gain must still exit 0: stdout=%s stderr=%s", gain.stdout(), gain.stderr())
            .isZero();
        assertThat(gain.stderr() + gain.stdout())
            .as("gain must surface the degraded warning: stdout=%s stderr=%s",
                gain.stdout(), gain.stderr())
            .containsIgnoringCase("analytics unavailable");
    }

    private static void corruptDatabase(Path dataDir) throws Exception {
        try (Stream<Path> stream = Files.list(dataDir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith("condense.db")) {
                    deleteRecursively(path);
                }
            }
        }
        Files.writeString(dataDir.resolve("condense.db"), "not a sqlite database");
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.walk(path)) {
                for (Path child : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(child);
                }
            }
        } else {
            Files.deleteIfExists(path);
        }
    }
}
