package com.condense.nativeimage;

import com.condense.bench.BenchStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Repeated native invocations with isolated dirs. Default 20 runs on every
 * Failsafe job; linux-x64 main/release pass {@code -Dcondense.soak.runs=300}.
 * Never skips. Timing gate is relative so shared runners cannot flake on ms.
 */
class NativeSoakIT {

    private static final int MIN_RUNS = 20;
    private static final int WINDOW = 10;
    private static final double MAX_LEAK_RATIO = 5.0;

    @TempDir
    Path tempDir;

    @Test
    void repeatedProxiedSuccessesDoNotSlowByFiveTimes() throws Exception {
        int runs = soakRuns();
        Path configDir = tempDir.resolve("config");
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);

        double[] millis = new double[runs];
        for (int i = 0; i < runs; i++) {
            NativeBinarySupport.TimedCliResult timed = NativeBinarySupport.timedRun(
                configDir, dataDir, NativeBinarySupport.trivialSucceedingCommand());
            assertThat(timed.result().exitCode())
                .as("soak run %d/%d stdout=%s stderr=%s",
                    i + 1, runs, timed.result().stdout(), timed.result().stderr())
                .isZero();
            millis[i] = timed.elapsedMillis();
            if (i < WINDOW || i >= runs - WINDOW) {
                System.out.printf("Soak run %d: %.0f ms%n", i + 1, millis[i]);
            }
        }

        double firstAvg = BenchStats.mean(Arrays.copyOfRange(millis, 0, WINDOW));
        double lastAvg = BenchStats.mean(Arrays.copyOfRange(millis, runs - WINDOW, runs));
        double ratio = BenchStats.ratio(lastAvg, firstAvg);
        System.out.printf("Soak first-%d mean: %.0f ms | last-%d mean: %.0f ms | ratio: %.2fx (gate: < %.0fx)%n",
            WINDOW, firstAvg, WINDOW, lastAvg, ratio, MAX_LEAK_RATIO);
        assertThat(ratio)
            .as("last-%d soak mean (%.0f ms) exceeded %.0fx the first-%d mean (%.0f ms)",
                WINDOW, lastAvg, MAX_LEAK_RATIO, WINDOW, firstAvg)
            .isLessThan(MAX_LEAK_RATIO);
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
