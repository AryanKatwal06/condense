# Performance baseline

This document records what Phase 10 (and prior Phase 17) measures, how, and which numbers are CI gates versus informational. Detailed concurrency architecture and virtual thread evidence live in `docs/concurrency-and-performance.md`.

## What is measured

| Surface | Where it runs | What it records | Gate? |
|---|---|---|---|
| Invocation overhead | JVM, `InvocationOverheadBenchmarkTest` in `mvn test` | Mean ± stddev, p50, p95 of an empty `FilterPipeline` vs one identity stage, interleaved warmup then 500 samples | **Relative + p95.** Identity mean must stay under **20×** the empty pipeline (`BenchStats.TIGHT_RELATIVE_OVERHEAD`). p95 under 5 ms. |
| Pipeline transformation | JVM, `FilterPipelineBenchmarkTest` | Direct strategy vs pipeline on npm / ls / eslint fixtures | **Relative + p95.** Pipeline mean must stay under **20×** the direct strategy on every printed row. p95 under bounded ceilings. |
| Override resolve / throughput | JVM, `FilterOverrideBenchmarkTest` | Uncached vs cached resolve, and default vs override pipeline execute | **Relative + p95.** Cached resolve must not be 20× slower than uncached. Override pipeline mean must stay under 20× default pipeline. |
| Structured parsers | JVM, `StructuredParserBenchmarkTest` | MSBuild binlog / TRX, Terraform machine-UI, Git status, Format reports, parser bombs | **p95 + graceful degradation.** TRX p95 < 50ms, Terraform UI p95 < 25ms, Git status p95 < 20ms, bombs capped in < 1s with no OOM. |
| Concurrent MCP | JVM, `McpConcurrentSessionBenchmarkTest` | 32 concurrent worker sessions dispatching tools and resources | **Throughput + p95.** Zero deadlocks, throughput > 2,000 ops/s, p95 < 5 ms. |
| Virtual vs Platform threads | JVM, `VirtualThreadsBenchmarkTest` | A/B comparison across 1, 4, 8, 16 concurrent drain streams | **Functional & Latency.** All streams drain truthfully with identical output; p95 bounded. |
| Native cold start | Failsafe `NativeBudgetIT` on every native CI OS | Five `--version` timings; **median** compared to a per-OS ceiling | **Yes.** Linux 1500 ms, macOS 2500 ms, Windows 4000 ms. Generous first-cut ceilings so shared runners cannot flake. |
| Native uncompressed size | Failsafe `NativeBudgetIT` and the `build.yml` / `release.yml` bash ceiling | Length of `condense-runner` (`.exe` on Windows) | **Yes.** Job fails above **80 MiB** (83,886,080 bytes). |
| Native soak & telemetry | Failsafe `NativeSoakIT` | N proxied successes with isolated dirs; tracks RSS memory slope, handle counts, temp cleanup | **Relative leak gate + zero temp leak.** Last-10 mean < 5× first-10 mean. Zero orphaned temp files. Handle delta bounded (< 250 on 20–300 runs, $\le 0.5$/run ceiling). Exports `target/soak-telemetry.json`. N=20 default; N=300 on main pushes; N=3000 on weekly scheduled workflow. |
| Native concurrency stress | Failsafe `NativeConcurrencyStressIT` | 16 parallel processes (12 writers, 4 readers) + slow-consumer backpressure | **Yes.** JDBC `PRAGMA integrity_check` must be `ok`. All child exit codes preserved truthfully. |
| Analytics fail-open | Failsafe `NativeAnalyticsFailOpenIT` | Proxied command after a corrupt `condense.db` | **Yes.** Child exit code stays 0. `condense gain` still exits 0 and prints `analytics unavailable`. |

## Why the size ceiling is 80 MiB

Measured on a green native CI at commit `fe4ad98` (Actions run 33750787542):

| Platform | Uncompressed bytes | MiB |
|---|---|---|
| linux-x64 | 55,111,168 | 52.55 |
| macos-aarch64 | 53,707,216 | 51.22 |
| windows-x64 | 54,988,800 | 52.44 |

80 MiB is about 50% headroom above those numbers. It is meant to catch a dependency or native-config accident, not to squeeze the image. linux-aarch64 uses the same 80 MiB bound.

## Why cold-start ceilings are generous

Wall-clock on shared GitHub runners is noisy. Phase 1 recorded `--version` timings as annotations and did not fail the job. Phase 17 asserts the **median** of five runs against a ceiling several times larger than the "<100 ms Linux / <150 ms macOS" figures that were marketing copy, not measured CI. After a green Phase 17 native matrix, record the observed medians here and consider tightening to about 4× measured if that still leaves headroom.

## How JVM overhead is measured

1. Warmup iterations, alternating order, discarded.
2. Measured iterations with `System.nanoTime()`, alternating execution order.
3. Mean, population stddev, p50, and p95 printed in microseconds.
4. Assert `mean(heavier) / max(mean(baseline), 0.001 µs) < 20.0` (`BenchStats.TIGHT_RELATIVE_OVERHEAD`).
5. Assert p95 remains strictly bounded under preset ceilings.

Shared helper: `com.condense.bench.BenchStats`.

## What is not measured here

Filter fidelity and baked savings floors are gated by the Phase 3 corpus (`docs/fidelity-corpus.md`). Graal native images are **not** bit-identical across rebuilds; release reproducibility is the runtime-dependency allowlist, CycloneDX SBOM, `checksums.txt`, and cosign signatures — see `SECURITY.md`.

