# Performance baseline

This document records what Phase 1 measures, how, and which numbers are CI gates versus informational.

## What is measured

| Surface | Where it runs | What it records | Gate? |
|---|---|---|---|
| Invocation overhead | JVM, `InvocationOverheadBenchmarkTest` in `mvn test` | Mean ± stddev of an empty `FilterPipeline` vs one identity stage, interleaved warmup then 500 samples | **Relative only.** Identity mean must stay under 100× the empty pipeline. Absolute microseconds are printed, never asserted. |
| Pipeline transformation | JVM, `FilterPipelineBenchmarkTest` | Direct strategy vs pipeline on npm / ls / eslint fixtures | No. Prints only. Hard time gates are a later phase. |
| Native cold start | `build.yml` native jobs | Five `--version` timings, published as job annotations | No. Informational. A later phase turns these into release budgets. |
| Native uncompressed size | `build.yml` native jobs | `stat` / `Get-Item` length of `condense-runner` (`.exe` on Windows) | **Yes.** Job fails above **80 MiB** (83,886,080 bytes). |

## Why the size ceiling is 80 MiB

Measured on the last green native CI at commit `fe4ad98` (Actions run 33750787542):

| Platform | Uncompressed bytes | MiB |
|---|---|---|
| linux-x64 | 55,111,168 | 52.55 |
| macos-aarch64 | 53,707,216 | 51.22 |
| windows-x64 | 54,988,800 | 52.44 |

80 MiB is about 50% headroom above those numbers. It is meant to catch a dependency or native-config accident, not to squeeze the image. linux-aarch64 had no artifact when the ceiling was set; it uses the same 80 MiB bound.

## How overhead is measured

The JVM overhead test uses the same interleaved method as the pipeline benchmarks:

1. 300 warmup iterations, alternating order, discarded.
2. 500 measured iterations with `System.nanoTime()`, still alternating which pipeline runs first.
3. Mean and population stddev printed in microseconds.
4. Assert `mean(identity) / max(mean(empty), 0.001 µs) < 100`.

Wall-clock on shared CI runners is noisy. A generous relative bound keeps this from flaking; it is not a performance target.

## What is not measured here

Filter fidelity and baked savings floors are gated by the Phase 3 corpus (`docs/fidelity-corpus.md`). Native per-command latency and release-time budgets still belong to later phases.
