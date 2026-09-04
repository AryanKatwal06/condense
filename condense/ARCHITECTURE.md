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
        └── TrackingRepository → creates SQLite schema at ~/.local/share/condense/condense.db
```

## File Responsibilities

| File | Package | Responsibility |
|------|---------|---------------|
| `CondenseMain.java` | `com.condense` | Quarkus entry point; wires picocli `CommandLine` with CDI factory |
| `CondenseRootCommand.java` | `com.condense` | Root `@Command`; handles `--help`, `--version`, `-v`, `-u` |
| `VersionProvider.java` | `com.condense` | Reads version from `version.properties`; implements `IVersionProvider` |
| `PlatformDirs.java` | `com.condense.core` | OS-specific path resolution, overridable via `CONDENSE_CONFIG_DIR` / `CONDENSE_DATA_DIR` |
| `CondenseConfig.java` | `com.condense.core` | Root config record with `HooksConfig` and `TeeConfig` nested records |
| `TeeMode.java` | `com.condense.core` | Enum: `FAILURES`, `ALWAYS`, `NEVER` with case-insensitive Jackson parsing |
| `ConfigLoader.java` | `com.condense.core` | Loads `config.toml` via Jackson TOML; returns defaults if file missing |
| `TrackingRepository.java` | `com.condense.core` | Lazy SQLite connection; auto-creates schema; insert/count methods |
| `TokenCounter.java` | `com.condense.core` | Static facade over `Utf8WeightedTokenEstimator` |
| `Utf8WeightedTokenEstimator.java` | `com.condense.core` | Code-point token estimate; UTF-8 file path; published p95 vs cl100k_base |

## Key Design Decisions

1. **Lazy initialization**: `TrackingRepository` opens the database connection on first use, not at startup. `ConfigLoader` reads the config file on first `load()` call. This keeps cold start time minimal.

2. **Non-fatal analytics**: `TrackingRepository.insert()` catches all `SQLException` and logs a warning — it never throws. Analytics must not break the primary CLI proxy function.

3. **Platform directories**: `PlatformDirs` follows XDG Base Directory Specification on Linux, standard macOS Library paths, and Windows `%APPDATA%`. Optional environment variables `CONDENSE_CONFIG_DIR` and `CONDENSE_DATA_DIR` take highest precedence (blank is treated as unset) so tests and power users can redirect state without touching the real user profile. Directories are created automatically on first access.

4. **Config defaults**: When no `config.toml` exists (first run), `CondenseConfig.defaults()` provides sensible production defaults. The app works correctly out of the box.

5. **GraalVM native image**: All reflection-heavy classes are registered in `reflect-config.json`. SQLite JNI classes are registered in `jni-config.json`. The `--no-fallback` flag ensures the build fails hard if any class is missing.

6. **Token estimates, not tokenizer counts**: Analytics use `utf8_weighted_v1` (code-point walk, dense CJK/emoji, Latin ÷ 4). Files and strings share one function. `condense gain` publishes the estimator name and a p95 relative-error bound vs cl100k_base. There is no tokenizer in the native image; the yardstick is test-scoped. See `docs/token-estimator.md`.

7. **Fidelity corpus**: Every domain filter has a row in the test-only `corpus/catalog.json`. `FidelityCorpusTest` requires 100% critical-signal retention and a baked savings floor. `GoldenLockTest` byte-locks filtered output. See `docs/fidelity-corpus.md`.

8. **Universal pipeline**: Every domain filter except the `PythonFilter` router extends `PipelineBackedFilter`. `apply()` is final: gates, then `FilterOverrideLoader.resolvePipeline`, then `FilterPipeline.execute`. Duplicate `@CommandFilter` prefixes from two classes fail `@PostConstruct` via `PrefixIndex`. Every regex in the filter package goes through `BoundedRegex` at 200 ms.

9. **Three-tier filter composition**: Project `.condense/filters.toml` (fail-open) then user-global `filters.toml` (fail-open) then builtin `classpath:filters/<name>.toml` via `BuiltinDefinitionCatalog` (fail-closed). Enumeration is `filters/index.toml` — never a classpath directory walk. `StageFactory` is a hardcoded switch. Schema v1 requires `schema_version = 1` and rejects unknown keys. See `docs/filter-schema.md`.

## Technology Stack

- **Java 21** — language level
- **Quarkus 3.11.0** — CDI, lifecycle, native image integration
- **picocli** (via `quarkus-picocli`) — CLI argument parsing
- **sqlite-jdbc 3.45.3.0** (Xerial) — embedded SQLite
- **Jackson 2.17.1** — JSON and TOML parsing
- **GraalVM Native Image** — ahead-of-time compilation for <100ms cold start
