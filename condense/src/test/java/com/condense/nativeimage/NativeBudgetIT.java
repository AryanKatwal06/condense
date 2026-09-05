package com.condense.nativeimage;

import com.condense.bench.BenchStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release budgets measured on the native binary. Never skips. Absolute
 * microseconds of JVM benches stay informational; this class gates process
 * start and uncompressed size on every CI platform.
 */
class NativeBudgetIT {

    static final long SIZE_CEILING_BYTES = 83_886_080L;
    static final long LINUX_COLD_START_MS = 1_500L;
    static final long MACOS_COLD_START_MS = 2_500L;
    static final long WINDOWS_COLD_START_MS = 4_000L;
    private static final int COLD_START_RUNS = 5;

    @TempDir
    Path tempDir;

    @Test
    void uncompressedBinaryStaysUnderEightyMib() {
        File binary = NativeBinarySupport.requireNativeBinary();
        long size = binary.length();
        System.out.printf("Native uncompressed size: %d bytes (ceiling %d = 80 MiB)%n",
            size, SIZE_CEILING_BYTES);
        assertThat(size)
            .as("uncompressed native image %s is %d bytes, exceeds 80 MiB ceiling",
                binary.getAbsolutePath(), size)
            .isLessThanOrEqualTo(SIZE_CEILING_BYTES);
    }

    @Test
    void versionMedianColdStartStaysUnderOsCeiling() throws Exception {
        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        long ceiling = coldStartCeilingMs();
        double[] millis = new double[COLD_START_RUNS];
        for (int i = 0; i < COLD_START_RUNS; i++) {
            NativeBinarySupport.TimedCliResult timed = NativeBinarySupport.timedRun(
                configDir, dataDir, "--version");
            assertThat(timed.result().exitCode())
                .as("run %d --version stdout=%s stderr=%s",
                    i + 1, timed.result().stdout(), timed.result().stderr())
                .isZero();
            millis[i] = timed.elapsedMillis();
            System.out.printf("Cold start run %d: %.0f ms%n", i + 1, millis[i]);
        }
        double median = BenchStats.median(millis);
        System.out.printf("Cold start median: %.0f ms (ceiling %d ms on %s)%n",
            median, ceiling, System.getProperty("os.name"));
        assertThat(median)
            .as("median of %d --version runs was %.0f ms, exceeds %d ms ceiling",
                COLD_START_RUNS, median, ceiling)
            .isLessThanOrEqualTo(ceiling);
    }

    static long coldStartCeilingMs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return WINDOWS_COLD_START_MS;
        }
        if (os.contains("mac")) {
            return MACOS_COLD_START_MS;
        }
        return LINUX_COLD_START_MS;
    }
}
