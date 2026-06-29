# Changelog

All notable changes to Condense (Java + GraalVM port) are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] — 2025-06-28

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

[0.1.0]: https://github.com/YOUR_ORG/condense/releases/tag/v0.1.0
