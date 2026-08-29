# Changelog

All notable changes to Condense (Java + GraalVM port) are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added / Improved
- **Declarative Filter Override System**: Introduced TOML-based schema and `FilterOverrideLoader` supporting a 3-tier precedence model (project-local `.condense/filters.toml` → user-global `filters.toml` → built-in compiled default) with fail-open error handling and strict security guards against symlink escapes, path traversal, and unauthorized code execution.
- **Configuration Validation Subcommand**: Added `condense config validate` (`ConfigValidateCommand`) with `--project`, `--global`, and `--file` options to provide structured, itemized diagnostics for filter override configurations.
- **Filter Pipeline Architecture & Hardening**: Introduced composable `FilterPipeline` abstraction with fail-open stage error handling, core strategy library, and hardened GraalVM reflection configuration.
- **Test Lifecycle Isolation**: Added defensive `<exclude>**/*IT.java</exclude>` configuration to `maven-surefire-plugin` with documented rationale, guarding against integration tests (such as `TrackingRepositoryNativeIT`) running during unit test execution under broadened CLI test selectors.

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
