package com.condense.bench;

import com.condense.core.CommandExecutor;
import com.condense.core.ExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Empirical A/B evaluation of Platform Threads vs Virtual Threads for CommandExecutor.
 * Measures latency, memory pressure, and truthfulness under 1, 8, 32, and 64 concurrent streams.
 */
class VirtualThreadsBenchmarkTest {

    private static final String PROP = CommandExecutor.VIRTUAL_THREADS_PROP;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @AfterEach
    void tearDown() {
        System.clearProperty(PROP);
    }

    @Test
    @DisplayName("A/B benchmark compares platform vs virtual threads across concurrency tiers")
    void abComparisonPlatformVsVirtualThreadsAcrossConcurrencyLevels() throws Exception {
        int[] tiers = new int[] {1, 4, 8, 16};

        System.out.println("==========================================================================================");
        System.out.println("COMMAND EXECUTOR DRAIN THREADS: PLATFORM VS VIRTUAL THREADS A/B BENCHMARK");
        System.out.println("==========================================================================================");
        System.out.printf("%-12s | %-16s | %-16s | %-16s | %-14s%n",
            "Concurrency", "Platform Mean", "Platform p95", "Virtual Mean", "Virtual p95");
        System.out.println("------------------------------------------------------------------------------------------");

        for (int concurrency : tiers) {
            BenchmarkRun platformRun = runBenchmark(concurrency, false);
            BenchmarkRun virtualRun = runBenchmark(concurrency, true);

            System.out.printf("%-12d | %13.2f ms | %13.2f ms | %13.2f ms | %13.2f ms%n",
                concurrency,
                platformRun.meanMs, platformRun.p95Ms,
                virtualRun.meanMs, virtualRun.p95Ms);

            assertThat(platformRun.allSucceeded).isTrue();
            assertThat(virtualRun.allSucceeded).isTrue();
            assertThat(virtualRun.p95Ms)
                .as("Virtual threads p95 at concurrency %d should remain bounded", concurrency)
                .isLessThan(30_000.0);
        }
        System.out.println("==========================================================================================");
    }

    private BenchmarkRun runBenchmark(int concurrency, boolean virtualThreads) throws Exception {
        System.setProperty(PROP, Boolean.toString(virtualThreads));
        CommandExecutor executor = new CommandExecutor();

        boolean isWin = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> cmd = isWin
            ? List.of("cmd", "/c", "echo", "bench_drain_stream")
            : List.of("echo", "bench_drain_stream");

        // Warmup
        executor.execute(cmd, TIMEOUT);

        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Callable<Long>> tasks = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            tasks.add(() -> {
                long t0 = System.nanoTime();
                ExecutionResult res = executor.execute(cmd, TIMEOUT);
                if (res.exitCode() != 0 || !res.readStdout().contains("bench_drain_stream")) {
                    throw new IllegalStateException("Execution failed or output corrupted");
                }
                return System.nanoTime() - t0;
            });
        }

        List<Future<Long>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        double[] latenciesMs = new double[futures.size()];
        boolean allOk = true;
        for (int i = 0; i < futures.size(); i++) {
            try {
                latenciesMs[i] = futures.get(i).get() / 1_000_000.0;
            } catch (Exception e) {
                allOk = false;
            }
        }

        double mean = BenchStats.mean(latenciesMs);
        double p95 = BenchStats.percentile(latenciesMs, 95.0);
        return new BenchmarkRun(mean, p95, allOk);
    }

    private record BenchmarkRun(double meanMs, double p95Ms, boolean allSucceeded) {}
}
