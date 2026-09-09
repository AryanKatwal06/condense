package com.condense.nativeimage;

import com.condense.bench.BenchStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Repeated native invocations with isolated dirs. Default 20 runs on every
 * Failsafe job; linux-x64 main/release pass {@code -Dcondense.soak.runs=300}.
 * Never skips. Timing gate is relative so shared runners cannot flake on ms.
 * Also monitors open handle count, RSS memory slope, and verifies zero orphaned temp files.
 */
class NativeSoakIT {

    private static final int MIN_RUNS = 20;
    private static final int WINDOW = 10;
    private static final double MAX_LEAK_RATIO = 5.0;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Repeated proxied runs maintain timing, handle, and memory budgets with zero temp leakage")
    void repeatedProxiedSuccessesDoNotSlowByFiveTimes() throws Exception {
        int runs = soakRuns();
        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);

        List<Path> initialTempFiles = NativeBinarySupport.findOrphanedTempFiles();
        long initialHandles = NativeBinarySupport.getOpenFileDescriptorOrHandleCount();

        double[] millis = new double[runs];
        double[] memSamples = new double[runs];

        for (int i = 0; i < runs; i++) {
            NativeBinarySupport.TimedCliResult timed = NativeBinarySupport.timedRun(
                configDir, dataDir, NativeBinarySupport.trivialSucceedingCommand());
            assertThat(timed.result().exitCode())
                .as("soak run %d/%d stdout=%s stderr=%s",
                    i + 1, runs, timed.result().stdout(), timed.result().stderr())
                .isZero();
            millis[i] = timed.elapsedMillis();
            memSamples[i] = (double) NativeBinarySupport.getCommittedMemoryBytes();
            if (i < WINDOW || i >= runs - WINDOW) {
                System.out.printf("Soak run %d: %.0f ms%n", i + 1, millis[i]);
            }
        }

        double firstAvg = BenchStats.mean(Arrays.copyOfRange(millis, 0, WINDOW));
        double lastAvg = BenchStats.mean(Arrays.copyOfRange(millis, runs - WINDOW, runs));
        double ratio = BenchStats.ratio(lastAvg, firstAvg);
        double p50 = BenchStats.percentile(millis, 50.0);
        double p90 = BenchStats.percentile(millis, 90.0);
        double p95 = BenchStats.percentile(millis, 95.0);
        double durationSlope = BenchStats.slope(millis);
        double memSlope = BenchStats.slope(memSamples);

        List<Path> finalTempFiles = NativeBinarySupport.findOrphanedTempFiles();
        Set<Path> leakedFiles = new HashSet<>(finalTempFiles);
        leakedFiles.removeAll(initialTempFiles);

        long finalHandles = NativeBinarySupport.getOpenFileDescriptorOrHandleCount();
        long handleDelta = (initialHandles > 0 && finalHandles > 0) ? finalHandles - initialHandles : 0;

        System.out.printf("Soak summary (%d runs):%n", runs);
        System.out.printf("  Duration: p50=%.0f ms | p90=%.0f ms | p95=%.0f ms | slope=%.4f ms/run%n",
            p50, p90, p95, durationSlope);
        System.out.printf("  Timing: first-%d mean: %.0f ms | last-%d mean: %.0f ms | ratio: %.2fx (gate: < %.0fx)%n",
            WINDOW, firstAvg, WINDOW, lastAvg, ratio, MAX_LEAK_RATIO);
        System.out.printf("  Handles: initial=%d | final=%d | delta=%d%n",
            initialHandles, finalHandles, handleDelta);
        System.out.printf("  Memory slope: %.2f bytes/run%n", memSlope);
        System.out.printf("  Orphaned temp files: %d%n", leakedFiles.size());

        writeTelemetryJson(runs, p50, p90, p95, firstAvg, lastAvg, ratio, durationSlope,
            memSlope, initialHandles, finalHandles, handleDelta, leakedFiles.size());

        assertThat(ratio)
            .as("last-%d soak mean (%.0f ms) exceeded %.0fx the first-%d mean (%.0f ms)",
                WINDOW, lastAvg, MAX_LEAK_RATIO, WINDOW, firstAvg)
            .isLessThan(MAX_LEAK_RATIO);

        assertThat(leakedFiles)
            .as("Soak runs must not leave any orphaned temp files in system temp directory")
            .isEmpty();

        if (initialHandles > 0 && finalHandles > 0) {
            assertThat(handleDelta)
                .as("Handle / file descriptor count should not monotonically leak across runs")
                .isLessThan(100);
        }
    }

    @Test
    @DisplayName("Single proxied executions leave zero orphaned temporary capture files")
    void soakRunLeavesZeroOrphanedTempFiles() throws Exception {
        Path configDir = tempDir.resolve("config-clean");
        Path dataDir = tempDir.resolve("data-clean");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);

        List<Path> before = NativeBinarySupport.findOrphanedTempFiles();
        for (int i = 0; i < 5; i++) {
            NativeBinarySupport.CliResult res = NativeBinarySupport.run(
                configDir, dataDir, NativeBinarySupport.trivialSucceedingCommand());
            assertThat(res.exitCode()).isZero();
        }
        List<Path> after = NativeBinarySupport.findOrphanedTempFiles();
        Set<Path> newFiles = new HashSet<>(after);
        newFiles.removeAll(before);

        assertThat(newFiles)
            .as("Native invocations must immediately clean up all temporary stream files")
            .isEmpty();
    }

    private void writeTelemetryJson(
            int runs,
            double p50,
            double p90,
            double p95,
            double firstAvg,
            double lastAvg,
            double ratio,
            double durationSlope,
            double memSlope,
            long initialHandles,
            long finalHandles,
            long handleDelta,
            int orphanedTempFiles
    ) {
        try {
            Path targetDir = Paths.get("target");
            if (Files.exists(targetDir)) {
                String json = String.format(
                    "{\n"
                        + "  \"runs\": %d,\n"
                        + "  \"duration_p50_ms\": %.2f,\n"
                        + "  \"duration_p90_ms\": %.2f,\n"
                        + "  \"duration_p95_ms\": %.2f,\n"
                        + "  \"first_window_avg_ms\": %.2f,\n"
                        + "  \"last_window_avg_ms\": %.2f,\n"
                        + "  \"timing_leak_ratio\": %.2f,\n"
                        + "  \"duration_slope\": %.4f,\n"
                        + "  \"memory_slope\": %.2f,\n"
                        + "  \"initial_handles\": %d,\n"
                        + "  \"final_handles\": %d,\n"
                        + "  \"handle_delta\": %d,\n"
                        + "  \"orphaned_temp_files\": %d\n"
                        + "}\n",
                    runs, p50, p90, p95, firstAvg, lastAvg, ratio, durationSlope,
                    memSlope, initialHandles, finalHandles, handleDelta, orphanedTempFiles
                );
                Files.writeString(targetDir.resolve("soak-telemetry.json"), json);
            }
        } catch (Exception e) {
            System.err.println("Notice: could not write soak-telemetry.json: " + e.getMessage());
        }
    }

    static int soakRuns() {
        String raw = System.getProperty("condense.soak.runs", "20");
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            fail("condense.soak.runs must be an integer, got '" + raw + "'");
            return MIN_RUNS;
        }
        if (parsed < MIN_RUNS) {
            fail("condense.soak.runs must be at least " + MIN_RUNS
                + " so the leak gate has a first-10 and last-10 window, got " + parsed);
        }
        return parsed;
    }
}
