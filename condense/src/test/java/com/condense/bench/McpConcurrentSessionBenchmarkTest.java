package com.condense.bench;

import com.condense.mcp.McpHandlers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Microbenchmarks for concurrent MCP client sessions.
 * Verifies lack of deadlocks, thread contention, and bounded p95 response times.
 */
class McpConcurrentSessionBenchmarkTest {

    private static final int CONCURRENCY = 32;
    private static final int CALLS_PER_THREAD = 100;

    @Test
    @DisplayName("Concurrent MCP sessions execute tools/list and resources/list without deadlock")
    void concurrentMcpSessionsExecuteWithoutDeadlock() throws Exception {
        McpHandlers handlers = new McpHandlers();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);

        List<Callable<List<Long>>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            tasks.add(() -> {
                List<Long> latencies = new ArrayList<>(CALLS_PER_THREAD);
                for (int j = 0; j < CALLS_PER_THREAD; j++) {
                    long t0 = System.nanoTime();
                    JsonNode tools = handlers.dispatch("tools/list", null);
                    JsonNode resources = handlers.dispatch("resources/list", null);
                    JsonNode ping = handlers.dispatch("ping", null);
                    long elapsed = System.nanoTime() - t0;
                    latencies.add(elapsed);

                    assertThat(tools).isNotNull();
                    assertThat(resources).isNotNull();
                    assertThat(ping).isNotNull();
                }
                return latencies;
            });
        }

        long benchStart = System.nanoTime();
        List<Future<List<Long>>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        boolean completed = pool.awaitTermination(30, TimeUnit.SECONDS);
        long totalNanos = System.nanoTime() - benchStart;

        assertThat(completed)
            .as("Concurrent MCP sessions should finish without deadlocks within 30s")
            .isTrue();

        List<Double> allLatencies = new ArrayList<>();
        for (Future<List<Long>> future : futures) {
            List<Long> latencies = future.get();
            for (Long l : latencies) {
                allLatencies.add((double) l);
            }
        }

        double[] latencyArray = allLatencies.stream().mapToDouble(Double::doubleValue).toArray();
        double p50Us = BenchStats.percentile(latencyArray, 50.0) / 1_000.0;
        double p95Us = BenchStats.percentile(latencyArray, 95.0) / 1_000.0;
        double meanUs = BenchStats.mean(latencyArray) / 1_000.0;
        double opsPerSec = (latencyArray.length * 1_000_000_000.0) / totalNanos;

        System.out.printf("MCP Concurrent Sessions (%d workers, %d total ops):%n", CONCURRENCY, latencyArray.length);
        System.out.printf("  Throughput: %.1f ops/sec%n", opsPerSec);
        System.out.printf("  Latency:    mean=%.2f µs | p50=%.2f µs | p95=%.2f µs%n", meanUs, p50Us, p95Us);

        assertThat(p95Us)
            .as("Concurrent MCP session p95 should remain under 5000 µs (5ms)")
            .isLessThan(5_000.0);
    }
}
