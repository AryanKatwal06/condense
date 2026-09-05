# Condense Architecture

## Overview

Condense is a CLI proxy that intercepts shell commands, filters their output using strategy-based filters, and logs token savings to a local SQLite database. The Java port uses Quarkus for CDI and native image support, picocli for CLI parsing, and Jackson for configuration loading.

## Component Diagram

```
condense --version / --help
        │
        ▼
  CondenseMain.java (QuarkusApplication)
        │
        ▼
  CondenseRootCommand (picocli @Command)
        │
        ├── VersionProvider → reads /com/condense/version.properties
        ├── ConfigLoader → reads ~/.config/condense/config.toml (or platform equivalent)
        │     └── CondenseConfig (record) + TeeMode (enum)
        ├── PlatformDirs → resolves config/data dirs per OS
        ├── TrackingRepository → SQLite at {dataDir}/condense.db (user_version, WAL, retention)
        └── McpCommand --start → hand-rolled stdio JSON-RPC (tools run/explain/read/discover, resources gain/doctor)
```

## File Responsibilities

| File | Package | Responsibility |
|------|---------|---------------|
| `CondenseMain.java` | `com.condense` | Quarkus entry point; wires picocli `CommandLine` with CDI factory |
| `CondenseRootCommand.java` | `com.condense` | Root `@Command`; handles `--help`, `--version`, `-v`, `-u`, `--format`; delegates proxy runs to `ProxyService` |
| `ProxyService.java` | `com.condense.core` | Shared proxy engine for the CLI and the MCP `run` tool |
| `McpCommand.java` | `com.condense.commands` | `condense mcp` — config snippet, or `--start` for stdio JSON-RPC |
| `McpServer.java` / `McpHandlers.java` | `com.condense.mcp` | Newline-delimited JSON-RPC loop and closed tool/resource switch |
| `VersionProvider.java` | `com.condense` | Reads version from `version.properties`; implements `IVersionProvider` |
| `PlatformDirs.java` | `com.condense.core` | OS-specific path resolution, overridable via `CONDENSE_CONFIG_DIR` / `CONDENSE_DATA_DIR` |
| `CondenseConfig.java` | `com.condense.core` | Root config record with `HooksConfig` and `TeeConfig` nested records |
| `TeeMode.java` | `com.condense.core` | Enum: `FAILURES`, `ALWAYS`, `NEVER` with case-insensitive Jackson parsing |
| `ConfigLoader.java` | `com.condense.core` | Loads `config.toml` via Jackson TOML; returns defaults if file missing |
| `TrackingRepository.java` | `com.condense.core` | Lazy SQLite via direct JDBC; migrates `user_version`, WAL, retention, filter outcomes |
| `SchemaMigrator.java` | `com.condense.persist` | Forward-only `PRAGMA user_version` migrations |
| `DoctorCommand.java` | `com.condense.doctor` | `condense doctor` — empty-gain diagnosis, text and JSON |
| `ExplainCommand.java` | `com.condense.explain` | `condense explain` — per-stage line and token accounting, plus `pipeline_mode` |
| `ReadCommand.java` | `com.condense.read` | `condense read` — language-aware source-file reading with original line numbers |
| `DiscoverCommand.java` | `com.condense.discover` | `condense discover` — recommend filter definition names from exact manifests |
| `DiscoverService.java` / `DiscoverRuleCatalog.java` | `com.condense.discover` | Bounded exact-path probes; classpath `discover/index.toml` only |
| `StreamingProxy.java` | `com.condense.core` | Live-print runner for STREAM pipelines and LIVE_RAW passthrough |
| `Utf8LineDecoder.java` | `com.condense.core` | Incremental UTF-8 line breaks across drain chunks |
| `TokenCounter.java` | `com.condense.core` | Static facade over `Utf8WeightedTokenEstimator` |
| `Utf8WeightedTokenEstimator.java` | `com.condense.core` | Code-point token estimate; UTF-8 file path; published p95 vs cl100k_base |
| `Document.java` | `com.condense.ir` | Schema-1 diagnostics envelope and kind-specific payloads |
| `DocumentBuilder.java` | `com.condense.ir` | Mutable sidecar on `FilterContext` for exemplar stages |
| `TextRenderer.java` / `JsonRenderer.java` | `com.condense.ir` | Compact-text (default) and schema-1 JSON renderers |
| `StrategyRegistry.java` | `com.condense.core` | CDI `@CommandFilter` beans, then leftover catalog prefixes on `CatalogBackedFilter` |
| `PrefixIndex.java` | `com.condense.core` | Longest-prefix map; overwrite only when the instance is the same object |
| `CatalogBackedFilter.java` | `com.condense.filter.pipeline` | Hosts leftover `filters/*.toml` definitions; not a CDI bean |

## Key Design Decisions

1. **Lazy initialization**: `TrackingRepository` opens the database connection on first use, not at startup. `ConfigLoader` reads the config file on first `load()` call. This keeps cold start time minimal.

2. **Non-fatal analytics**: `TrackingRepository.insert()` catches all `SQLException` and logs a warning — it never throws. Analytics must not break the primary CLI proxy function.

3. **Platform directories**: `PlatformDirs` follows XDG Base Directory Specification on Linux, standard macOS Library paths, and Windows `%APPDATA%`. Optional environment variables `CONDENSE_CONFIG_DIR` and `CONDENSE_DATA_DIR` take highest precedence (blank is treated as unset) so tests and power users can redirect state without touching the real user profile. Directories are created automatically on first access.

4. **Config defaults**: When no `config.toml` exists (first run), `CondenseConfig.defaults()` provides sensible production defaults. The app works correctly out of the box.

5. **GraalVM native image**: All reflection-heavy classes are registered in `reflect-config.json`. SQLite JNI classes are registered in `jni-config.json`. The `--no-fallback` flag ensures the build fails hard if any class is missing.

6. **Token estimates, not tokenizer counts**: Analytics use `utf8_weighted_v1` (code-point walk, dense CJK/emoji, Latin ÷ 4). Files and strings share one function. `condense gain` publishes the estimator name and a p95 relative-error bound vs cl100k_base. There is no tokenizer in the native image; the yardstick is test-scoped. See `docs/token-estimator.md`.

7. **Fidelity corpus**: Every domain filter has a row in the test-only `corpus/catalog.json`. `FidelityCorpusTest` requires 100% critical-signal retention and a baked savings floor. `GoldenLockTest` byte-locks filtered output. See `docs/fidelity-corpus.md`.

8. **Universal pipeline**: Every domain filter except the `PythonFilter` router extends `PipelineBackedFilter`. `apply()` is final: gates, then `FilterOverrideLoader.resolvePipeline`, then `FilterPipeline.execute` (session walk). Duplicate prefixes fail `@PostConstruct` via `PrefixIndex` unless the existing entry is the same instance (two `CatalogBackedFilter` hosts cannot share a prefix). Every regex in the filter package goes through `BoundedRegex` at 200 ms.

9. **Three-tier filter composition**: Project `.condense/filters.toml` (TOFU + capability ceiling) then user-global `filters.toml` (trusted by location) then builtin `classpath:filters/<name>.toml` via `BuiltinDefinitionCatalog` (fail-closed). Enumeration is `filters/index.toml` — never a classpath directory walk. After CDI beans, `StrategyRegistry` registers leftover catalog `commands` on `CatalogBackedFilter`. `StageFactory` is a hardcoded switch. Schema v1 requires `schema_version = 1` and rejects unknown keys. Builtin-only optional keys are `select_input` and `[gate]`. See `docs/filter-schema.md` and `docs/trust.md`.

10. **Trust and provenance**: Project overrides are skipped until `condense config trust` (or a CI hatch that also has a listed CI indicator). `FilterResult.of` stamps `condense[filtered]`; impersonating lines become `condense[quoted]`. See `docs/trust.md`.

11. **Derived streaming**: Each `FilterStage` declares `streamability()`. The pipeline is STREAM only when every stage is `order_local` or `windowed`; otherwise CAPTURE. `CondenseRootCommand` live-prints STREAM and unmatched passthrough (`LIVE_RAW`) through `StreamingProxy`. Capture-to-disk remains the tee/token/fail-open backstop. See `docs/streaming.md`.

12. **Structured diagnostics IR**: Exemplar stages populate a `DocumentBuilder` sidecar on `FilterContext`. `TextRenderer` is the default CLI; `JsonRenderer` emits schema 1. Everyone else, gates, and IR-build failures wrap existing text as `kind=opaque`. `--format json` waits for the child to exit. See `docs/ir.md`.

13. **MCP over stdio**: `condense mcp --start` is a hand-rolled JSON-RPC server (no extra Maven dependency). `run` returns the Phase 11 envelope; `explain` / `read` / `discover` / `gain` / `doctor` reuse existing records. MCP paths go through `ReadPathGate` / the same narrow-only root as `condense read`. Logs go to stderr so stdout stays JSON-RPC-only. MCP is the preferred agent path; hooks are the fallback. See `docs/mcp.md`.

14. **Hook integrity**: `HookBackup` copies an existing third-party config before merge (fail-closed). `HookIntegrity` SHA-256s Condense-owned scripts into `hook_baselines`. Analytics schema target is 2. Matched hook commands deny with a retry string containing `condense `; they never rewrite-and-allow. See `docs/HOOKS.md`.

15. **Recommend-only discovery**: `condense discover` loads `classpath:discover/*.toml` via `discover/index.toml`. Precedence is an explicit integer per family (lower wins). Probes are exact contained paths with hard Java caps. Output is a recommendation list of existing filter definition names. `StrategyRegistry` is unchanged. See `docs/discover.md`.

## Technology Stack

- **Java 21** — language level
- **Quarkus 3.11.0** — CDI, lifecycle, native image integration
- **picocli** (via `quarkus-picocli`) — CLI argument parsing
- **sqlite-jdbc 3.45.3.0** (Xerial) — embedded SQLite
- **Jackson 2.17.1** — JSON and TOML parsing
- **GraalVM Native Image** — ahead-of-time compilation for <100ms cold start
