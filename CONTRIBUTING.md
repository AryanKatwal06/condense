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

Native integration tests (`NativeCliIT`, `NativeAnalyticsIT`, `NativeCorpusIT`, `NativeBuiltinDefinitionIT`, `NativeTrustIT`, `NativePersistenceIT`, `NativeExplainIT`) require `native.image.path` and **fail rather than skip** if the binary is missing. They set `CONDENSE_CONFIG_DIR` and `CONDENSE_DATA_DIR` on the child process so they never write the developer's real analytics database. Project `.condense/filters.toml` is skipped until `condense config trust` (or a CI hatch with a listed CI indicator). See [docs/trust.md](docs/trust.md). Filtered `FilterResult.of` output starts with `condense[filtered]`. Persistence schema, WAL, retention, and `condense doctor` are in [docs/persistence.md](docs/persistence.md). Per-stage explainability is `condense explain` — see [docs/explain.md](docs/explain.md).

See [docs/perf-baseline.md](docs/perf-baseline.md) for what CI measures (invocation overhead, native size ceiling, cold start). Token estimates are documented in [docs/token-estimator.md](docs/token-estimator.md); `TokenEstimatorAccuracyTest` fails `mvn test` if p95 error vs cl100k_base exceeds the published bound. Filter fidelity is documented in [docs/fidelity-corpus.md](docs/fidelity-corpus.md); `FidelityCorpusTest` fails if a catalogued critical signal is dropped or a baked savings floor is missed.

## Adding a New Command Filter

**Contribution Bar:** A new compressing filter must add a row to `condense/src/test/resources/corpus/catalog.json` with `savings_floor` ≥ 60, measured with `utf8_weighted_v1` (see [docs/token-estimator.md](docs/token-estimator.md) and [docs/fidelity-corpus.md](docs/fidelity-corpus.md)). `FidelityCorpusTest` enforces 100% critical-signal retention. Entries that structurally cannot compress must declare `savings_exemption` (`passthrough`, `too_small`, `verbose_mode`, `failure_verbatim`, `intentional_identity`). Do not set `meets_contribution_bar: false` on new work — that flag is only for grandfathered fixtures that already shipped below 60%.

Adding support for a new command (e.g. `helm`) takes these steps. The default pipeline is data (`filters/helm.toml`); the Java class only owns dispatch, gates, and a definition name. See [docs/filter-schema.md](docs/filter-schema.md).

### 1. Create the filter class

```java
// src/main/java/com/condense/filter/cloud/HelmFilter.java
package com.condense.filter.cloud;

import com.condense.annotation.CommandFilter;
import com.condense.core.*;
import com.condense.filter.pipeline.PipelineBackedFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@CommandFilter("helm")
@ApplicationScoped
public class HelmFilter extends PipelineBackedFilter {

    public HelmFilter() {}

    @Inject
    public HelmFilter(FilterOverrideLoader overrideLoader) {
        super(overrideLoader);
    }

    @Override
    protected String definitionName() {
        return "helm";
    }

    @Override
    protected FilterResult beforePipeline(String command, ExecutionResult result,
                                         CondenseConfig config, int verbose, boolean ultraCompact) {
        if (!result.succeeded()) return FilterResult.passthrough(result);
        return null;
    }
}
```

Do **not** override `buildPipeline()`. It is final on `PipelineBackedFilter` and loads `classpath:filters/<definitionName>.toml`.

**Rules for implementations**:
- Extend `PipelineBackedFilter`. Do not override `apply`. `PythonFilter` is the only router exception.
- Gates (failure, verbose, size, grep exit 1) belong in `beforePipeline`. Parsing belongs in named `FilterStage`s declared in the TOML.
- Every regex goes through `BoundedRegex` (200 ms). Do not call `Pattern.matcher` directly.
- Always return `FilterResult.passthrough(result)` on non-zero exit unless the filter specifically handles failures
- Never throw out of a stage in a way that changes the child's exit code — the pipeline fail-opens
- Keep the class stateless — one instance is reused for all invocations
- Add a catalog row and a `corpus/golden/{id}.txt` lock. `GoldenLockTest` fails on a silent output change.

### 2. Write the builtin definition and list it in the index

Create `src/main/resources/filters/helm.toml` with `schema_version = 1`, `name = "helm"`, `commands` that exactly match `@CommandFilter`, a `[[stages]]` list, and at least one `[[tests]]` case. Add `"helm"` to `src/main/resources/filters/index.toml`. Enumeration is that index file only — never walk the directory. `BuiltinDefinitionValidator` runs at Maven `process-classes` and fails the build on a missing `schema_version`, unknown key, duplicate name/command, or a failing inline test.

### 3. Create fixture files and a catalog row

```
src/test/resources/fixtures/helm/typical.txt   — real helm output (copy from terminal)
src/test/resources/fixtures/helm/failure.txt   — failed command output
```

Add an entry to `src/test/resources/corpus/catalog.json` with `critical_signals` (literal substrings that must survive filtering) and either `savings_floor` ≥ 60 or a listed `savings_exemption`. Add the matching golden under `src/test/resources/corpus/golden/`. `CorpusCoverageTest` fails `mvn test` if the new `FilterStrategy` has no row.

### 4. Write tests

```java
// src/test/java/com/condense/filter/cloud/HelmFilterTest.java
package com.condense.filter.cloud;

import com.condense.core.*;
import com.condense.filter.FilterTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HelmFilterTest extends FilterTestSupport {

    private HelmFilter filter;
    private CondenseConfig config;

    @BeforeEach
    void setUp() { filter = new HelmFilter(); config = CondenseConfig.defaults(); }

    @Test
    void typicalOutput_isCompressed() throws Exception {
        FilterResult r = filter.apply("helm",
            success(fixture("helm", "typical")), config, 0, false);
        assertCompressed(r);
    }

    @Test
    void failureOutput_isPassedThrough() {
        FilterResult r = filter.apply("helm",
            failure(1, "Error: no releases found"), config, 0, false);
        assertPassthrough(r);
    }
}
```

### 5. Keep reflect-config.json in sync

`ReflectConfigDriftTest` runs in `mvn test` and fails if a new `FilterStrategy` (or a Jackson-bound config/analytics type) is missing from `src/main/resources/META-INF/native-image/reflect-config.json`, or if a class name is registered twice. Add an entry for the new filter:

```json
{ "name": "com.condense.filter.cloud.HelmFilter",
  "allDeclaredConstructors": true, "allDeclaredMethods": true }
```

Do not treat this as an optional checklist item. If the JSON is stale, the JVM test fails before a 10-minute native build would.

### 6. Verify and submit

```bash
mvn test                              # includes ReflectConfigDriftTest
mvn package -Pnative -DskipTests      # native image must build with --no-fallback
mvn failsafe:integration-test failsafe:verify -DskipITs=false
./target/condense-runner helm list    # smoke test with a real helm install
```

Then open a pull request. Confirm:

- [ ] Filter class implemented with `@CommandFilter`, `@ApplicationScoped`, and `definitionName()`
- [ ] Builtin `filters/<name>.toml` plus an `index.toml` entry
- [ ] Fixture files created with real command output
- [ ] Catalog row in `corpus/catalog.json` (60% floor or an enumerated exemption; critical signals retained)
- [ ] Tests written covering typical + failure cases
- [ ] `ReflectConfigDriftTest` and `FidelityCorpusTest` pass (`mvn test`)
- [ ] Native image builds without fallback
- [ ] Native Failsafe ITs pass when the binary is present

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
