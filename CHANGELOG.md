# Changelog

All notable changes to Condense (Java + GraalVM port) are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Multi-prefix filters (`npm install`, `eslint`, `pip install`, and the other `@CommandFilters` commands) are registered in `StrategyRegistry`. `CommandFilter` was not `@Repeatable`, so the real CLI treated those commands as passthrough. Native proof is `NativeStreamingIT`.
- `condense gain --top` and `condense gain --top 10` now render the top-N table. The old `if (top != 10)` branch treated the default value as “flag absent,” so the documented `--top 10` example showed the summary panel instead.
- `condense doctor` is `@Unremovable` so Quarkus no longer strips the Picocli bean from the native image. Without that, `PicocliBeansFactory` failed with a CDI unused-bean error and `NativePersistenceIT` exited 1 on every platform.
- `condense doctor --format json` no longer exits 1 in the native binary. The report now uses `LinkedHashMap` / `ArrayList` so Jackson can serialize empty maps and warning lists without GraalVM `ImmutableCollections` reflection. `NativePersistenceIT` failed on every CI platform because of this.
- `condense uninstall --purge` no longer aborts when `{dataDir}/tee/` or `{configDir}/filters.toml` exist. Those are Condense-owned and are removed with the rest of the allowlisted tree.
- On Windows, `condense` now resolves PATHEXT shims such as `pytest.cmd` / `npm.cmd` before launching the child process. `ProcessBuilder` does not apply PATHEXT, which made `NativeCorpusIT` (and real `condense pytest`) fail with empty stdout.

### Added / Improved
- **Incremental streaming.** Proxy mode can print filtered lines while a long command is still running. Mode is derived from each stage's declared streamability (`order_local` / `windowed` / `finalize_only` / `document`) — there is no `--stream` flag and no TOML `streamable` key. `npm install` and `docker build` emit irrevocable progress as it arrives, then the existing one-line success summary at exit. The proxy waits until the child exits unless `CONDENSE_COMMAND_TIMEOUT_SEC` is a positive integer. A 10 MB stream cap destroys the child and fail-opens instead of replacing the exit code. `condense explain` reports `pipeline_mode` and per-stage `streamability`. Native proof is `NativeStreamingIT`. See [docs/streaming.md](docs/streaming.md).
- **Explainability.** `condense explain` prints per-stage line and token accounting, dropped/added samples, and the precedence tier that supplied the pipeline (`project` / `global` / `builtin`). JSON includes the same `estimator` object as `gain`. `--input` / `--stdin` explain a fixture without executing. Explain does not write analytics. Native proof is `NativeExplainIT`. See [docs/explain.md](docs/explain.md).
- **Persistence reliability.** Existing analytics databases are migrated with `PRAGMA user_version` (target 1). Every open enables WAL and `busy_timeout=5000`, prunes `commands`, `filter_outcomes`, and tee files older than 90 days, and records filter fail-open events. `condense doctor` (text and `--format json`) names why `gain` is empty. Native proof is `NativePersistenceIT` migrating a runtime-created v0 file inside the binary. See [docs/persistence.md](docs/persistence.md).
- **Trust boundary and capability model.** Project `.condense/filters.toml` is skipped until `condense config trust` pins its SHA-256 (or a CI hatch that also has a listed CI indicator). `CONDENSE_TRUST_PROJECT_FILTERS` alone — for example from `.envrc` — does not apply project overrides. After trust, the file still cannot use stages above the granted class (`reduce` / `reshape` / `rewrite`); a missing grant skips the whole file. See [docs/trust.md](docs/trust.md).
- **Output provenance.** Every `FilterResult.of` line starts with `condense[filtered]`. Impersonating lines become `condense[quoted]`. Passthrough is unstamped. The 51-row golden lock was updated for that header; inline TOML `[[tests]]` are unchanged.
- **Declarative filter schema v1.** Every compressing filter's default pipeline loads from `classpath:filters/<name>.toml` (31 files plus `index.toml`; `PythonFilter` stays a Java router). Documents require `schema_version = 1` and reject unknown keys. `StageFactory` is a hardcoded switch covering generic stages and named command summaries. Builtin definitions fail-closed; user overrides still fail-open. `BuiltinDefinitionValidator` runs at Maven `process-classes` so `mvn package -Pnative -DskipTests` still checks the resources. Override files without `schema_version` fail-open at runtime; `condense config validate` reports the error. See [docs/filter-schema.md](docs/filter-schema.md).
- Every domain filter now runs through `FilterPipeline`. Duplicate command prefixes fail at startup, built-in regexes share the 200 ms bound already used by overrides, and migrated filters share one `FilterOverrideLoader` instead of constructing a private cache each. Filtered corpus output is byte-locked in `corpus/golden/`.
- CI now fails `mvn test` if a domain filter drops a declared critical signal or falls below its baked savings floor. The catalog is `condense/src/test/resources/corpus/catalog.json`. See [docs/fidelity-corpus.md](docs/fidelity-corpus.md).
- Replaced the mixed byte/UTF-16 `/4` token heuristic with `utf8_weighted_v1`, a UTF-8 code-point estimator used for both files and strings. `condense gain` now labels counts as estimates and reports a p95 relative-error bound of 35% vs cl100k_base. See [docs/token-estimator.md](docs/token-estimator.md).
- Set the compiler language level to Java 21 so bytecode matches GraalVM 21 CI and the documented toolchain.
- Added `CONDENSE_CONFIG_DIR` and `CONDENSE_DATA_DIR` overrides in `PlatformDirs` so tests and power users can redirect config and analytics state on every OS, including macOS.
- Native integration tests now run via Failsafe in CI (`NativeCliIT`, `NativeAnalyticsIT`, `NativeCorpusIT`, `NativePersistenceIT`) on linux-x64, linux-aarch64, macos-aarch64, and windows-x64, using isolated directories instead of the real user database.
- Added `ReflectConfigDriftTest` to fail `mvn test` when a `FilterStrategy` or Jackson-bound type is missing from `reflect-config.json`, and removed duplicate native-image registrations.
- Added linux-aarch64 native jobs to CI and release (`ubuntu-24.04-arm`) so the installer download of that artifact is honest.
- Recorded a JVM invocation-overhead baseline and an 80 MiB uncompressed native-image size ceiling. See [docs/perf-baseline.md](docs/perf-baseline.md).
- **Declarative Filter Override System**: Introduced TOML-based schema and `FilterOverrideLoader` supporting a 3-tier precedence model (project-local `.condense/filters.toml` → user-global `filters.toml` → built-in compiled default) with fail-open error handling and strict security guards against symlink escapes, path traversal, and unauthorized code execution.
- **Configuration Validation Subcommand**: Added `condense config validate` (`ConfigValidateCommand`) with `--project`, `--global`, and `--file` options to provide structured, itemized diagnostics for filter override configurations.
- **Filter Pipeline Architecture & Hardening**: Introduced composable `FilterPipeline` abstraction with fail-open stage error handling, core strategy library, and hardened GraalVM reflection configuration.
- **Test Lifecycle Isolation**: Added defensive `<exclude>**/*IT.java</exclude>` configuration to `maven-surefire-plugin` with documented rationale, guarding against native integration tests running during unit test execution under broadened CLI test selectors.

## [1.0.0] — 2026-08-24

### Fixed
- **Native Image SQLite Persistence**: Resolved issue where SQLite analytics tracking failed in GraalVM native binary builds due to driver runtime initialization constraints; connections now initialize directly and reliably persist data across all platforms (BUG-002).
- **Analytics Degradation Signal**: Added clear `⚠ analytics unavailable — persistence failed, see logs` warning in `condense gain` if analytics persistence encounters an error, preventing misleading zero-value dashboards (BUG-003).
- **macOS Architecture Artifact Labeling**: Corrected release artifact naming for Apple Silicon (`condense-macos-aarch64`) to match runner architecture, and updated installer script with clear guidance for Intel macOS users (NEW-009).
- **Repository Rename Cleanup**: Corrected remaining stale repository references from `code-condenser` to `condense` across documentation, installers, packaging manifests, and self-update endpoints (BUG-001).
- **Filter and Hook Error Logging**: Added diagnostic debug and warning logging to previously-silent exception blocks in token filtering and AI tool hook installation/removal (BUG-004, BUG-005).
- **Native JSON Serialization**: Registered analytics report records with GraalVM reflection metadata (`@RegisterForReflection`), fixing `--format json` output in native binary runs.
- **Update Command Security & Checksum Verification**: Added SHA-256 integrity verification, HTTP error handling, and Content-Type validation to `UpdateCommand`.
- Cleaned up repository, added Apache 2.0 LICENSE and NOTICE files, and pinned `macos-latest` runners to `macos-15`.

### Added / Improved
- **CI Smoke Test Assertions**: Strengthened CI and release workflows to structurally assert `gain --format json` output and verify positive command recording across Linux, macOS, and Windows runners (NEW-006, NEW-007).
- **Windows MSVC Build Validation**: Added strict errorlevel checking after `vcvarsall.bat` environment setup in Windows native-image build actions (NEW-008).
- **Phase 3 Verification Suite**: Added comprehensive verification workflow with soak and concurrency testing.
- **Native Binary Integration Test**: Added rigorous native integration test suite to validate SQLite persistence in compiled binaries.
- **Troubleshooting Documentation**: Added comprehensive troubleshooting section in `README.md` explaining analytics degradation causes and resolutions.
- Full provenance verification via Sigstore/cosign and CycloneDX SBOM integration.

## [1.0.0-rc1] — 2026-06-30

### Added

- **42 command filters** covering git, cargo, pytest, go test, npm, jest, vitest,
  eslint, tsc, ruff, docker, kubectl, aws, ls, grep, rg, find, cat, make, mvn,
  gradle, pip install, golangci-lint, and more
- **12 filter strategies**: stats extraction, failure focus, grouping,
  deduplication, JSON structure, tree compression, ANSI stripping,
  state machine, NDJSON streaming, and more
- **`condense gain`** analytics command with ASCII bar chart, history table,
  top-N commands, daily/weekly breakdown, project scope, and JSON output
- **`condense init`** hook installer for Claude Code, Cursor, Gemini CLI,
  Windsurf, Copilot, and Cline
- **`condense config`** management: list, get, set, reset
- **GraalVM native image**: cold start <100ms on Linux, <150ms on macOS
- **SQLite analytics**: every command logged with raw/filtered token counts
- **Tee system**: raw output saved on failure for AI retrieval
- **Ultra-compact mode** (`-u`): single-line ASCII icon output
- **Verbose mode** (`-v`, `-vv`, `-vvv`): progressive detail levels
- **Shell completions**: bash, zsh, fish
- **Man page**: `condense(1)`
- **Static Linux binaries** (musl): run on any Linux distro without glibc dependency
- **Cross-platform**: Linux x64, Linux aarch64, macOS x64, macOS aarch64

### Architecture

- Java 21 + Quarkus 3.11 + picocli 4.7 + GraalVM Native Image
- SQLite via sqlite-jdbc (Xerial bundled)
- TOML config via jackson-dataformat-toml
- Zero network dependencies during normal operation
- Apache License 2.0

[1.0.1]: https://github.com/AryanKatwal06/condense/releases/tag/v1.0.1
[1.0.0]: https://github.com/AryanKatwal06/condense/releases/tag/v1.0.0
[1.0.0-rc1]: https://github.com/AryanKatwal06/condense/releases/tag/v1.0.0-rc1
