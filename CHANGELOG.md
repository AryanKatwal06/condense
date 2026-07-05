# Changelog

All notable changes to Condense (Java + GraalVM port) are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.1] — 2026-07-04

### Fixed
- Added Apache 2.0 LICENSE and NOTICE files
- Cleaned up git repository (removed ~20 junk tracked files including large binaries)
- Pinned `macos-latest` runners to `macos-15` to avoid silent migration issues
- Documented Windsurf fail-open behavior and cascade retry caveat in README
- Confirmed Gemini CLI and Windsurf hook installer idempotency
- Fixed `YOUR_ORG` placeholders in documentation
- Synchronized `version.properties` with pom.xml version

## [1.0.0] — 2026-07-03

### Added
- Official v1.0.0 initial stable release!
- Contains all features from release candidates including 42 command filters, 12 filter strategies, and `condense gain` analytics.
- Updated release pipeline to use Node.js 24-compatible GitHub Actions.
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

[1.0.1]: https://github.com/AryanKatwal06/code-condenser/releases/tag/v1.0.1
[1.0.0]: https://github.com/AryanKatwal06/code-condenser/releases/tag/v1.0.0
[1.0.0-rc1]: https://github.com/AryanKatwal06/code-condenser/releases/tag/v1.0.0-rc1
