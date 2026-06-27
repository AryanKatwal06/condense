# Zap Architecture — Phase 1

## Overview

Zap is a CLI proxy that intercepts shell commands, filters their output using strategy-based filters, and logs token savings to a local SQLite database. The Java port uses Quarkus for CDI and native image support, picocli for CLI parsing, and Jackson for configuration loading.

## Component Diagram

```
zap --version / --help
        │
        ▼
  ZapMain.java (QuarkusApplication)
        │
        ▼
  ZapRootCommand (picocli @Command)
        │
        ├── VersionProvider → reads /com/zapproxy/version.properties
        ├── ConfigLoader → reads ~/.config/zap/config.toml (or platform equivalent)
        │     └── ZapConfig (record) + TeeMode (enum)
        ├── PlatformDirs → resolves config/data dirs per OS
        └── TrackingRepository → creates SQLite schema at ~/.local/share/zap/zap.db
```

## File Responsibilities

| File | Package | Responsibility |
|------|---------|---------------|
| `ZapMain.java` | `com.zapproxy` | Quarkus entry point; wires picocli `CommandLine` with CDI factory |
| `ZapRootCommand.java` | `com.zapproxy` | Root `@Command`; handles `--help`, `--version`, `-v`, `-u` |
| `VersionProvider.java` | `com.zapproxy` | Reads version from `version.properties`; implements `IVersionProvider` |
| `PlatformDirs.java` | `com.zapproxy.core` | OS-specific path resolution (XDG on Linux, Library on macOS, AppData on Windows) |
| `ZapConfig.java` | `com.zapproxy.core` | Root config record with `HooksConfig` and `TeeConfig` nested records |
| `TeeMode.java` | `com.zapproxy.core` | Enum: `FAILURES`, `ALWAYS`, `NEVER` with case-insensitive Jackson parsing |
| `ConfigLoader.java` | `com.zapproxy.core` | Loads `config.toml` via Jackson TOML; returns defaults if file missing |
| `TrackingRepository.java` | `com.zapproxy.core` | Lazy SQLite connection; auto-creates schema; insert/count methods |

## Key Design Decisions

1. **Lazy initialization**: `TrackingRepository` opens the database connection on first use, not at startup. `ConfigLoader` reads the config file on first `load()` call. This keeps cold start time minimal.

2. **Non-fatal analytics**: `TrackingRepository.insert()` catches all `SQLException` and logs a warning — it never throws. Analytics must not break the primary CLI proxy function.

3. **Platform directories**: `PlatformDirs` follows XDG Base Directory Specification on Linux, standard macOS Library paths, and Windows `%APPDATA%`. Directories are created automatically on first access.

4. **Config defaults**: When no `config.toml` exists (first run), `ZapConfig.defaults()` provides sensible production defaults. The app works correctly out of the box.

5. **GraalVM native image**: All reflection-heavy classes are registered in `reflect-config.json`. SQLite JNI classes are registered in `jni-config.json`. The `--no-fallback` flag ensures the build fails hard if any class is missing.

## Technology Stack

- **Java 21** — language level
- **Quarkus 3.11.0** — CDI, lifecycle, native image integration
- **picocli** (via `quarkus-picocli`) — CLI argument parsing
- **sqlite-jdbc 3.45.3.0** (Xerial) — embedded SQLite
- **Jackson 2.17.1** — JSON and TOML parsing
- **GraalVM Native Image** — ahead-of-time compilation for <100ms cold start
