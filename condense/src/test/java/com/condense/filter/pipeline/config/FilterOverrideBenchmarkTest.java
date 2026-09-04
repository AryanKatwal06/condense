package com.condense.filter.pipeline.config;

import com.condense.core.PlatformDirs;
import com.condense.filter.pipeline.FilterPipeline;
import com.condense.filter.strategy.AnsiStripStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class FilterOverrideBenchmarkTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Rigorous Benchmark: Filter Override Resolution & Execution Throughput")
    void benchmarkFilterOverrideResolutionAndThroughput() throws IOException {
        System.out.println("==========================================================================================================================");
        System.out.println("PHASE 2 CLOSEOUT BENCHMARK: OVERRIDE RESOLUTION (UNCACHED VS CACHED) & PIPELINE THROUGHPUT");
        System.out.println("==========================================================================================================================");
        System.out.printf("%-40s | %-20s | %-20s | %-12s | %-10s%n",
            "Benchmark Scenario", "Uncached (us +/- std)", "Cached (us +/- std)", "Diff (us)", "Speedup");
        System.out.println("--------------------------------------------------------------------------------------------------------------------------");

        FilterPipeline defaultPipeline = FilterPipeline.builder()
            .addStage(AnsiStripStrategy.INSTANCE)
            .build();

        // -------------------------------------------------------------------------
        // Case 1: Common case (no override file exists)
        // -------------------------------------------------------------------------
        Path emptyProject = tempDir.resolve("empty-benchmark-project");
        Files.createDirectories(emptyProject);

        FilterOverrideLoader uncachedLoader = new FilterOverrideLoader(new PlatformDirs());
        FilterOverrideLoader cachedLoader = new FilterOverrideLoader(new PlatformDirs());

        // Warm up cachedLoader once so it populates negative cache
        cachedLoader.resolvePipeline("npm install", defaultPipeline, emptyProject);

        runBenchmark(
            "Case 1: No Override (Negative Cache)",
            () -> {
                uncachedLoader.invalidateCache();
                uncachedLoader.resolvePipeline("npm install", defaultPipeline, emptyProject);
            },
            () -> cachedLoader.resolvePipeline("npm install", defaultPipeline, emptyProject)
        );

        System.out.println("--------------------------------------------------------------------------------------------------------------------------");

        // -------------------------------------------------------------------------
        // Case 2: Override present (declarative TOML override exists on disk)
        // -------------------------------------------------------------------------
        Path overrideProject = tempDir.resolve("override-benchmark-project");
        Path condenseDir = overrideProject.resolve(".condense");
        Files.createDirectories(condenseDir);

        String toml = """
            schema_version = 1
            [filters."npm install"]
            stages = [
              { strategy = "ansi_strip" }
            ]
            """;
        Files.writeString(condenseDir.resolve("filters.toml"), toml);

        FilterOverrideLoader uncachedOverrideLoader = new FilterOverrideLoader(new PlatformDirs());
        FilterOverrideLoader cachedOverrideLoader = new FilterOverrideLoader(new PlatformDirs());

        // Warm up cachedOverrideLoader once to populate cache
        cachedOverrideLoader.resolvePipeline("npm install", defaultPipeline, overrideProject);

        runBenchmark(
            "Case 2: Override Present (Full Cache)",
            () -> {
                uncachedOverrideLoader.invalidateCache();
                uncachedOverrideLoader.resolvePipeline("npm install", defaultPipeline, overrideProject);
            },
            () -> cachedOverrideLoader.resolvePipeline("npm install", defaultPipeline, overrideProject)
        );

        System.out.println("--------------------------------------------------------------------------------------------------------------------------");

        // -------------------------------------------------------------------------
        // Case 3: Pure pipeline execution throughput (safe regex / timeout wrapper)
        // -------------------------------------------------------------------------
        String inputPayload = "\u001B[32m[SUCCESS]\u001B[0m installed 42 packages in 1.4s\n".repeat(100);
        FilterPipeline resolvedCachedPipeline = cachedOverrideLoader.resolvePipeline("npm install", defaultPipeline, overrideProject);

        runThroughputBenchmark(
            "Case 3: Pipeline Execution Throughput",
            () -> defaultPipeline.execute(inputPayload),
            () -> resolvedCachedPipeline.execute(inputPayload)
        );

        System.out.println("==========================================================================================================================");
    }

    private void runBenchmark(String label, Runnable uncachedTask, Runnable cachedTask) {
        // 1. Warmup: 300 iterations interleaved and discarded
        for (int i = 0; i < 300; i++) {
            if ((i & 1) == 0) {
                uncachedTask.run();
                cachedTask.run();
            } else {
                cachedTask.run();
                uncachedTask.run();
            }
        }

        // 2. Measurement: 500 interleaved iterations with nanosecond precision
        int iterations = 500;
        double[] uncachedNanos = new double[iterations];
        double[] cachedNanos = new double[iterations];

        for (int i = 0; i < iterations; i++) {
            if ((i & 1) == 0) {
                long t0 = System.nanoTime();
                uncachedTask.run();
                long t1 = System.nanoTime();
                uncachedNanos[i] = (t1 - t0);

                long t2 = System.nanoTime();
                cachedTask.run();
                long t3 = System.nanoTime();
                cachedNanos[i] = (t3 - t2);
            } else {
                long t2 = System.nanoTime();
                cachedTask.run();
                long t3 = System.nanoTime();
                cachedNanos[i] = (t3 - t2);

                long t0 = System.nanoTime();
                uncachedTask.run();
                long t1 = System.nanoTime();
                uncachedNanos[i] = (t1 - t0);
            }
        }

        double meanUncachedUs = mean(uncachedNanos) / 1_000.0;
        double stdUncachedUs = stdDev(uncachedNanos, mean(uncachedNanos)) / 1_000.0;
        double meanCachedUs = mean(cachedNanos) / 1_000.0;
        double stdCachedUs = stdDev(cachedNanos, mean(cachedNanos)) / 1_000.0;

        double diffUs = meanCachedUs - meanUncachedUs;
        double speedup = meanCachedUs > 0 ? (meanUncachedUs / meanCachedUs) : 1.0;

        String uncachedStr = String.format("%.2f +/- %.1f", meanUncachedUs, stdUncachedUs);
        String cachedStr = String.format("%.2f +/- %.1f", meanCachedUs, stdCachedUs);

        System.out.printf("%-40s | %20s | %20s | %+10.2f us | %8.1fx%n",
            label, uncachedStr, cachedStr, diffUs, speedup);
    }

    private void runThroughputBenchmark(String label, Runnable baselineTask, Runnable targetTask) {
        for (int i = 0; i < 300; i++) {
            if ((i & 1) == 0) {
                baselineTask.run();
                targetTask.run();
            } else {
                targetTask.run();
                baselineTask.run();
            }
        }

        int iterations = 500;
        double[] baselineNanos = new double[iterations];
        double[] targetNanos = new double[iterations];

        for (int i = 0; i < iterations; i++) {
            if ((i & 1) == 0) {
                long t0 = System.nanoTime();
                baselineTask.run();
                long t1 = System.nanoTime();
                baselineNanos[i] = (t1 - t0);

                long t2 = System.nanoTime();
                targetTask.run();
                long t3 = System.nanoTime();
                targetNanos[i] = (t3 - t2);
            } else {
                long t2 = System.nanoTime();
                targetTask.run();
                long t3 = System.nanoTime();
                targetNanos[i] = (t3 - t2);

                long t0 = System.nanoTime();
                baselineTask.run();
                long t1 = System.nanoTime();
                baselineNanos[i] = (t1 - t0);
            }
        }

        double meanBaseUs = mean(baselineNanos) / 1_000.0;
        double stdBaseUs = stdDev(baselineNanos, mean(baselineNanos)) / 1_000.0;
        double meanTargetUs = mean(targetNanos) / 1_000.0;
        double stdTargetUs = stdDev(targetNanos, mean(targetNanos)) / 1_000.0;

        double diffUs = meanTargetUs - meanBaseUs;
        double ratio = meanBaseUs > 0 ? (meanTargetUs / meanBaseUs) : 1.0;

        String baseStr = String.format("%.2f +/- %.1f", meanBaseUs, stdBaseUs);
        String targetStr = String.format("%.2f +/- %.1f", meanTargetUs, stdTargetUs);

        System.out.printf("%-40s | %20s | %20s | %+10.2f us | %8.2fx%n",
            label, baseStr, targetStr, diffUs, ratio);
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    private static double stdDev(double[] values, double mean) {
        double sumSq = 0.0;
        for (double v : values) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / values.length);
    }
}
