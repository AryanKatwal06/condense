# Concurrency, GC, and Sustained Native Performance

This document records Condense's evidence-based concurrency architecture, native resource leak telemetry, virtual thread evaluation, and hardened benchmark baselines.

---

## 1. Concurrency Architecture & Threading Model

Condense proxies arbitrary shell commands, draining child process stdout and stderr concurrently onto bounded temporary files while feeding live pipeline decoders.

### Platform Threads vs. Virtual Threads Empirical Evaluation

In Java 21+, virtual threads (`Thread.ofVirtual()`) allow millions of lightweight threads to run concurrently. However, Condense's primary usage is an interactive CLI proxy where a single child command creates exactly two drain threads (`condense-stdout` and `condense-stderr`).

An empirical A/B benchmark (`VirtualThreadsBenchmarkTest`) was executed across concurrency tiers (1, 4, 8, 16 parallel processes):

| Concurrency Tier | Platform Mean (ms) | Platform p95 (ms) | Virtual Mean (ms) | Virtual p95 (ms) |
|---|---|---|---|---|
| **1 Worker (CLI baseline)** | 2722.49 | 2722.49 | 2818.01 | 2818.01 |
| **4 Workers** | 4399.45 | 4427.65 | 4336.09 | 4368.73 |
| **8 Workers** | 5285.25 | 5412.81 | 5655.82 | 5726.77 |
| **16 Workers** | 12677.20 | 12896.58 | 13069.77 | 13249.91 |

### Evidence-Based Adoption Threshold

1. **Throughput & Latency**: For standard CLI invocations (1–4 concurrent commands), platform threads demonstrate identical or slightly lower latency than virtual threads because they avoid virtual scheduler initialization.
2. **Memory Footprint**: Each platform thread reserves an OS thread stack (typically 1 MiB on 64-bit platforms). For 1–4 processes, 2–8 platform threads consume negligible stack memory (<8 MiB).
3. **High-Concurrency Threshold**: In long-lived multi-session environments (such as MCP server mode handling >32 concurrent child executions), 64 platform threads consume >64 MiB of OS stack memory. Under this condition, virtual threads provide substantial memory savings.
4. **Adoption Policy**:
   - **Default**: Platform daemon threads with descriptive names (`condense-stdout`, `condense-stderr`).
   - **Configuration Gate**: `CONDENSE_VIRTUAL_THREADS=true` or `-Dcondense.concurrency.virtual.threads=true`.
   - **Fail-Open Contract**: If virtual thread creation fails in any restricted native environment, `CommandExecutor` catches `Throwable` and immediately falls back to platform threads.

---

## 2. High-Concurrency Native Stress & Contention

`NativeConcurrencyStressIT` tests Condense under sustained multi-process contention:

- **16 Concurrent Native Processes**: 12 writers running proxied commands (`echo`) simultaneously writing to `condense.db` alongside 4 concurrent readers executing `condense gain --format json`.
- **SQLite Database Integrity**: Verified using `PRAGMA integrity_check` directly through the Xerial SQLite JDBC driver. In all stress tests, `PRAGMA integrity_check` returns `ok` with zero database corruption and zero lock-induced failures.
- **Backpressure & Slow Consumers**: Stream consumers that artificially throttle reading do not cause pipe deadlocks or premature process termination; all 100% of child output bytes are faithfully drained.

---

## 3. Native Soak Telemetry & Resource Leak Gates

`NativeSoakIT` runs 20 invocations by default on every Failsafe job, 300 runs on CI `main` pushes, and 3,000 runs on scheduled weekly CI workflows.

### Metrics Collected

- **Duration Percentiles**: `p50`, `p90`, `p95` wall-clock duration in milliseconds.
- **Duration Leak Ratio**: Ratio of the last 10-run window mean to the first 10-run window mean (enforced ceiling: $< 5.0\times$).
- **Duration Linear Slope**: Measured via linear regression across all $N$ runs.
- **Memory (RSS / Working Set) Slope**: Measured across run sequence to detect monotonic memory growth.
- **File Descriptors & OS Handles**: Monitored via Unix MXBean (`getOpenFileDescriptorCount`) on Linux/macOS and process handle counts on Windows. Asserts zero monotonic handle leakage ($\Delta < 250$ handles on 20–300 runs, with $\le 0.5$ handles/run ceiling on large runs).
- **Temporary File Cleanup**: Verifies that `condense-stream-*.log` and `condense-test*.tmp` files created during execution are immediately deleted via `ExecutionResult.cleanup()`. **Zero orphaned files permitted.**

### Telemetry JSON Output

Every soak test exports `condense/target/soak-telemetry.json`:

```json
{
  "runs": 300,
  "duration_p50_ms": 14.50,
  "duration_p90_ms": 18.20,
  "duration_p95_ms": 21.00,
  "first_window_avg_ms": 15.10,
  "last_window_avg_ms": 15.30,
  "timing_leak_ratio": 1.01,
  "duration_slope": 0.0020,
  "memory_slope": 0.00,
  "initial_handles": 182,
  "final_handles": 184,
  "handle_delta": 2,
  "orphaned_temp_files": 0
}
```

Scheduled GitHub Actions workflows (`.github/workflows/soak-and-perf-baselines.yml`) execute 3,000-run soaks and publish telemetry as protected workflow artifacts with 90-day retention.

---

## 4. Hardened Benchmark Engine & Allocation Baselines

### Tighter Relative Overhead Ceiling

The legacy 100× relative overhead guard has been replaced with `BenchStats.TIGHT_RELATIVE_OVERHEAD = 20.0` (20×) across:
- `InvocationOverheadBenchmarkTest`
- `FilterPipelineBenchmarkTest`
- `FilterOverrideBenchmarkTest`

### Microbenchmark Baselines

1. **TRX Report Parser** (`StructuredParserBenchmarkTest`):
   - Mean: ~8.7 ms, p50: ~8.6 ms, p95: ~12.8 ms.
   - Allocation: ~89 KB/op.
   - p95 Gate: $< 50$ ms.
2. **Terraform Machine UI & Resource Graph** (`StructuredParserBenchmarkTest`):
   - p95: ~2.4 ms.
   - p95 Gate: $< 25$ ms.
3. **Git Status & Format Report** (`StructuredParserBenchmarkTest`):
   - p95: ~2.3 ms.
   - p95 Gate: $< 20$ ms.
4. **Hostile Parser Bombs** (`StructuredParserBenchmarkTest`):
   - Deeply nested JSON / 15,000 repetitive lines processed and capped in ~108 ms.
   - Gate: $< 1,000$ ms with zero `OutOfMemoryError` or `StackOverflowError`.
5. **Concurrent MCP Sessions** (`McpConcurrentSessionBenchmarkTest`):
   - 32 concurrent worker threads executing 3,200 operations.
   - Throughput: $> 4,800$ ops/sec.
   - Latency: p50: ~0.47 ms, p95: ~3.9 ms.
   - p95 Gate: $< 5.0$ ms.
