# Performance baseline

This document records what Phase 17 measures, how, and which numbers are CI gates versus informational.

## What is measured

| Surface | Where it runs | What it records | Gate? |
|---|---|---|---|
| Invocation overhead | JVM, `InvocationOverheadBenchmarkTest` in `mvn test` | Mean ± stddev of an empty `FilterPipeline` vs one identity stage, interleaved warmup then 500 samples | **Relative only.** Identity mean must stay under 100× the empty pipeline. Absolute microseconds are printed, never asserted. |
| Pipeline transformation | JVM, `FilterPipelineBenchmarkTest` | Direct strategy vs pipeline on npm / ls / eslint fixtures | **Relative only.** Pipeline mean must stay under 100× the direct strategy on every printed row. Absolute microseconds are printed. |
| Override resolve / throughput | JVM, `FilterOverrideBenchmarkTest` | Uncached vs cached resolve, and default vs override pipeline execute | **Relative only.** Cached resolve must not be 100× slower than uncached. Override pipeline mean must stay under 100× the default pipeline. |
| Native cold start | Failsafe `NativeBudgetIT` on every native CI OS | Five `--version` timings; **median** compared to a per-OS ceiling | **Yes.** Linux 1500 ms, macOS 2500 ms, Windows 4000 ms. These are generous first-cut ceilings so shared runners cannot flake. Annotations in `build.yml` remain informational. |
| Native uncompressed size | Failsafe `NativeBudgetIT` and the `build.yml` / `release.yml` bash ceiling | Length of `condense-runner` (`.exe` on Windows) | **Yes.** Job fails above **80 MiB** (83,886,080 bytes). |
| Native soak | Failsafe `NativeSoakIT` | N proxied successes (`echo` / `cmd /c echo`) with isolated `CONDENSE_*` dirs | **Relative leak gate.** Last-10 mean must stay under 5× the first-10 mean. Default N=20 on every native job. N=300 on linux-x64 for pushes to `main`, tag releases, and `phase3-verification.yml`. |
| Native concurrency | Failsafe `NativeConcurrencyIT` | Five parallel invocations, JDBC `PRAGMA integrity_check`, `gain` `total_commands >= 1` | **Yes.** Integrity must be `ok`. All five rows are not required (Windows writers are not fully serialized). |
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

1. 300 warmup iterations, alternating order, discarded.
2. 500 measured iterations with `System.nanoTime()`, still alternating which pipeline runs first.
3. Mean and population stddev printed in microseconds.
4. Assert `mean(heavier) / max(mean(baseline), 0.001 µs) < 100`.

Shared helper: `com.condense.bench.BenchStats`.

## What is not measured here

Filter fidelity and baked savings floors are gated by the Phase 3 corpus (`docs/fidelity-corpus.md`). Graal native images are **not** bit-identical across rebuilds; release reproducibility is the runtime-dependency allowlist, CycloneDX SBOM, `checksums.txt`, and cosign signatures — see `SECURITY.md`.
