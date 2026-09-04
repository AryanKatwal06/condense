# Fidelity corpus

Phase 3 makes filter quality machine-checkable. Savings percentages and “keep the failures” are no longer prose in CONTRIBUTING.md; `mvn test` fails when either contract is broken.

## Catalog

`condense/src/test/resources/corpus/catalog.json` is the versioned list. It is a **test resource**, not shipped in the native image.

| Field | Rule |
|---|---|
| `schema_version` | Required. Must be `1`. Unknown keys fail the loader. |
| `id` | Unique. Convention `{command-dir}/{fixture-stem}`. |
| `command` | Prefix `StrategyRegistry` would look up (`pytest`, `git status`, `kubectl get pods`). |
| `fixture` | Classpath path under `src/test/resources/`. |
| `exit_code` | Child exit used to build `ExecutionResult`. |
| `critical_signals` | Literal, case-sensitive substrings that **must** appear in filtered output. Failures, error lines, and exit-relevant summary lines — not passing-test noise. |
| `savings_floor` | Integer 0–100. Mutually exclusive with `savings_exemption`. |
| `savings_exemption` | Closed set: `passthrough`, `too_small`, `verbose_mode`, `failure_verbatim`, `intentional_identity`. |
| `meets_contribution_bar` | `false` only for grandfathered entries whose measured savings are below 60%. New compressing rows must omit this (or set it true) and use `savings_floor` ≥ 60. |

Exactly one of `savings_floor` or `savings_exemption` is required.

## Gates

| Test | What it fails on |
|---|---|
| `CorpusCatalogLoadTest` | Unknown JSON keys, missing `schema_version`, invalid exemption / floor pairing |
| `CorpusCoverageTest` | A `FilterStrategy` other than `PassthroughStrategy` with no catalog row; a new compressing row with floor &lt; 60; a missing fixture file |
| `FidelityCorpusTest` | A missing critical signal, or `savingsPct()` below the baked floor |
| `CorpusFuzzTest` | `apply` throws, or a signal that is still in the mutated input is missing from filtered output |
| `NativeCorpusIT` | The native binary, with a PATH-stubbed `pytest`, drops `test_mul` / `failed` or fails to pass through exit code 1 |

Filters are constructed with their no-arg constructor (same as existing `*FilterTest` classes), not via CDI.

## Floor policy

Measured with `utf8_weighted_v1` (`FilterResult.savingsPct()`).

1. If the entry already saves ≥ 60%, bake `savings_floor: 60`.
2. If it compresses but is below 60%, bake the measured value minus a 5-point cushion, and set `meets_contribution_bar: false`.
3. If it structurally cannot compress (including fixtures whose filtered form is the same size or longer, e.g. kubectl highlighting unhealthy pods), omit the floor and set `savings_exemption`.
4. A **new** compressing catalog entry must meet 60% or `CorpusCoverageTest` fails.

Phase 3 does not rewrite filter `apply()` methods to chase 60%. A weak typical fixture is a recorded fact and a regression tripwire, not a license to change agent-visible output here.

## Fuzz

Seed **`20260904`**. 25 iterations per compressing entry. Mutations that keep every critical substring already present in the raw fixture: a few prefix noise lines (skipped for git porcelain and JSON) and extra blank lines. Non-blank suffix noise is omitted because last-line filters (e.g. `make`) would drop a trailing summary that is still in the input. ANSI wrapping of existing lines is omitted because it changes how header-classified parsers (git log, git diff) read a line. Prefix/suffix noise is not applied to JSON fixtures, because a single extra line makes the payload unparseable and is not a fair retention probe.

## Native smoke

There is no `condense replay` CLI. `NativeCorpusIT` writes `fixtures/pytest/typical.txt` next to a stub `pytest` (`pytest.cmd` on Windows) that prints that fixture and exits 1, prepends that directory to `PATH`, and runs the native binary as `condense pytest`. Proof is the IT class name in `build.yml` native job logs.
