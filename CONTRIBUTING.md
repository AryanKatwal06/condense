# Contributing to Condense

This guide covers adding new command filters, running tests, and submitting pull requests.

## Prerequisites

- GraalVM JDK 21 with `native-image` on PATH
- Maven 3.9+
- Git
- (Linux only for static builds) `musl-tools`: `sudo apt-get install musl-tools`

## Development Setup

```bash
git clone https://github.com/AryanKatwal06/condense.git
cd condense
mvn verify          # builds, tests, confirms everything works
```

## Running Tests

```bash
mvn test                          # unit tests (JVM), including ReflectConfigDriftTest
mvn verify                        # full build + JVM test suite (Failsafe ITs stay skipped)
mvn package -Pnative -DskipTests  # native image build (takes 2-5 minutes)
mvn failsafe:integration-test failsafe:verify -DskipITs=false   # native ITs against the built binary
```

Native integration tests (`NativeCliIT`, `NativeAnalyticsIT`, `NativeCorpusIT`, `NativeBuiltinDefinitionIT`, `NativeTrustIT`, `NativePersistenceIT`, `NativeExplainIT`, `NativeStreamingIT`, `NativeReadIT`, `NativeIrIT`, `NativeMcpIT`, `NativeHookIT`) require `native.image.path` and **fail rather than skip** if the binary is missing. They set `CONDENSE_CONFIG_DIR` and `CONDENSE_DATA_DIR` on the child process so they never write the developer's real analytics database. Project `.condense/filters.toml` is skipped until `condense config trust` (or a CI hatch with a listed CI indicator). See [docs/trust.md](docs/trust.md). Filtered `FilterResult.of` output starts with `condense[filtered]`. Persistence schema, WAL, retention, and `condense doctor` are in [docs/persistence.md](docs/persistence.md). Per-stage explainability is `condense explain` — see [docs/explain.md](docs/explain.md). Source-file reading is `condense read` — see [docs/read.md](docs/read.md). The stdio MCP server is `condense mcp --start` — see [docs/mcp.md](docs/mcp.md). Hooks are the fallback; see [docs/HOOKS.md](docs/HOOKS.md).

See [docs/perf-baseline.md](docs/perf-baseline.md) for what CI measures (invocation overhead, native size ceiling, cold start). Token estimates are documented in [docs/token-estimator.md](docs/token-estimator.md); `TokenEstimatorAccuracyTest` fails `mvn test` if p95 error vs cl100k_base exceeds the published bound. Filter fidelity is documented in [docs/fidelity-corpus.md](docs/fidelity-corpus.md); `FidelityCorpusTest` fails if a catalogued critical signal is dropped or a baked savings floor is missed.

## Adding a New Command Filter

**Contribution Bar:** A new compressing filter must add a row to `condense/src/test/resources/corpus/catalog.json` with `savings_floor` ≥ 60, measured with `utf8_weighted_v1` (see [docs/token-estimator.md](docs/token-estimator.md) and [docs/fidelity-corpus.md](docs/fidelity-corpus.md)). `FidelityCorpusTest` enforces 100% critical-signal retention. Entries that structurally cannot compress must declare `savings_exemption` (`passthrough`, `too_small`, `verbose_mode`, `failure_verbatim`, `intentional_identity`). Do not set `meets_contribution_bar: false` on new work — that flag is only for grandfathered fixtures that already shipped below 60%.

Adding support for a new command (e.g. `helm install`) is **catalog-only by default**. Write `filters/helm.toml`, list it in `index.toml`, and add a corpus row. `StrategyRegistry` registers leftover `commands` on a `CatalogBackedFilter` host. Do **not** add a Java `@CommandFilter` class unless you need a handwritten gate, a new `StageFactory` alias, or a router. See [docs/filter-schema.md](docs/filter-schema.md).

### 1. Write the builtin definition and list it in the index

Create `src/main/resources/filters/helm.toml` with `schema_version = 1`, `name = "helm"`, `commands` that are not already claimed by a `@CommandFilter` (use specific prefixes such as `helm install` — not a bare catch-all that would steal `helm version`), a `[[stages]]` list using existing aliases, and at least one `[[tests]]` case. Optional builtin-only keys: `select_input` and `[gate]` (`passthrough_verbose`, `passthrough_max_lines`, `passthrough_nonzero_exit`). Add `"helm"` to `src/main/resources/filters/index.toml`. Enumeration is that index file only — never walk the directory. `BuiltinDefinitionValidator` runs at Maven `process-classes` and fails the build on a missing `schema_version`, unknown key, duplicate name/command, or a failing inline test.

Do **not** override `buildPipeline()` if you later add a Java host. It is final on `PipelineBackedFilter` and loads `classpath:filters/<definitionName>.toml`.

**Rules**:
- Prefer leftover catalog dispatch. A Java `PipelineBackedFilter` is the exception path. Do not override `apply`. `PythonFilter` is the only router exception.
- Gates that the builtin `[gate]` table can express stay in TOML. Handwritten gates belong in `beforePipeline`. Parsing belongs in named `FilterStage`s declared in the TOML.
- Every regex goes through `BoundedRegex` (200 ms). Do not call `Pattern.matcher` directly.
- Always return `FilterResult.passthrough(result)` on non-zero exit unless the filter specifically handles failures
- Never throw out of a stage in a way that changes the child's exit code — the pipeline fail-opens
- Keep any Java class stateless — one instance is reused for all invocations. Per-run state belongs on `StageSession`, not the stage bean.
- Streamability is declared in Java (`FilterStage.streamability()`), never in TOML. A pipeline streams only when every stage is `order_local` or `windowed`. See [docs/streaming.md](docs/streaming.md).
- Add a catalog row and a `corpus/golden/{id}.txt` lock. `GoldenLockTest` fails on a silent output change.
- New command summaries that need a typed IR kind should populate a `DocumentBuilder` on `FilterContext` and emit `TextRenderer.render(...)` so text and JSON stay one model. Leftover catalog commands stay `kind=opaque`. See [docs/ir.md](docs/ir.md). Do not add a TOML `output` / `format` / `json` key (`format` is a `regex_capture` template param only).

### 2. Create fixture files and a catalog row

```
src/test/resources/fixtures/helm/typical.txt   — real helm output (copy from terminal)
src/test/resources/fixtures/helm/failure.txt   — failed command output
```

Add an entry to `src/test/resources/corpus/catalog.json` with `critical_signals` (literal substrings that must survive filtering) and either `savings_floor` ≥ 60 or a listed `savings_exemption`. Add the matching golden under `src/test/resources/corpus/golden/`. `CorpusCoverageTest` fails `mvn test` if a Java domain filter or an `index.toml` name has no row.

### 3. Write tests

Inline `[[tests]]` on the TOML plus the corpus row are enough for a leftover definition. Add a Java `*FilterTest` only when you added a Java class.

```java
CatalogBackedFilter filter = new CatalogBackedFilter("helm");
FilterResult r = filter.apply("helm install",
    success(fixture("helm", "typical")), CondenseConfig.defaults(), 0, false);
assertCompressed(r);
```

### 4. Keep reflect-config.json in sync

The gate is `ReflectConfigDriftTest`, not a manual ritual. It runs in `mvn test` and fails if a new `FilterStrategy` (or a Jackson-bound config/analytics type) is missing from `src/main/resources/META-INF/native-image/reflect-config.json`, or if a class name is registered twice. Leftover catalog commands reuse `CatalogBackedFilter` (already registered). A new Java filter class will demand this shape:

```json
{ "name": "com.condense.filter.cloud.HelmFilter",
  "allDeclaredConstructors": true, "allDeclaredMethods": true }
```

If the JSON is stale, the JVM test fails before a 10-minute native build would.

### 5. Verify and submit

```bash
mvn test                              # includes ReflectConfigDriftTest
mvn package -Pnative -DskipTests      # native image must build with --no-fallback
mvn failsafe:integration-test failsafe:verify -DskipITs=false
./target/condense-runner helm list    # smoke test with a real helm install
```

Then open a pull request. Confirm:

- [ ] Builtin `filters/<name>.toml` plus an `index.toml` entry (no new Java class unless a stage or handwritten gate is missing)
- [ ] Fixture files created with real command output
- [ ] Catalog row in `corpus/catalog.json` (60% floor or an enumerated exemption; critical signals retained)
- [ ] Inline `[[tests]]` green via `process-classes`; Java tests only if you added a class
- [ ] `ReflectConfigDriftTest` and `FidelityCorpusTest` pass (`mvn test`)
- [ ] Native image builds without fallback
- [ ] Native Failsafe ITs pass when the binary is present (`NativeCatalogIT` covers leftover dispatch)

## Code Style

- Java 21, no wildcard imports
- Public methods have Javadoc when the behavior isn't obvious from the signature alone — skip it for simple getters, setters, and self-explanatory utility methods
- Records for data carriers (`ExecutionResult`, `FilterResult`, etc.)
- Try-with-resources for all SQL and I/O
- `@ApplicationScoped` for CDI beans, never `@Singleton`

## Pull Request Process

1. Fork the repository
2. Create a feature branch: `git checkout -b feat/helm-filter`
3. Implement, test, and verify (see above)
4. Open a PR against `main`
5. CI must be green (JVM tests + native image build)
6. One approving review required

## Reporting Issues

Use GitHub Issues for:
- Bug reports (include `condense --version` output and steps to reproduce)
- Feature requests (new command filters, analytics features)

For security issues, see [SECURITY.md](SECURITY.md).
