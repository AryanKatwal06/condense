# Condense vs. Zap: Technical Architecture and Capability Comparison

This document provides a technical, evidence-backed evaluation comparing **Condense** (`v1.0.1`) against **Zap** (commit `d9498bb`, 25 May 2026).

Both tools share a common mission: compressing shell command output before sending it into AI agent context windows to save tokens, reduce latency, and preserve model reasoning capacity. However, their underlying architectures, safety models, and platform guarantees diverge in important ways.

---

## Technical Summary Matrix

| Evaluation Dimension | Zap (`d9498bb`) | Condense (`1.0.1`) | Winner / Standing |
| :--- | :--- | :--- | :--- |
| **1. Command Coverage** | 90+ supported commands | 140+ commands across 108 TOML definitions | **Condense** |
| **2. Filter Depth (npm)** | Line skips and pattern matching | Structured installation and audit summary | **Condense** |
| **3. Filter Depth (Go test)** | Dedicated `go_cmd.rs` with test event handling | `GoTestSummaryStage` with concurrent diagnostic tracking & JSON panic extraction | **Parity** |
| **4. Filter Depth (Jest/Vitest)** | Dedicated parsers for Jest/Vitest test trees | ANSI-stripped, bounded per-failure budgeting in `JestSummaryStage` & `VitestSummaryStage` | **Parity** |
| **5. Structured Output (JSON)** | 3-tier degradation contract (`Full`, `Degraded`, `Passthrough`) | Typed Intermediate Representation (`Document`, `TestDocument`, `BuildDocument`) covering 25% of catalog | **Parity** |
| **6. Declarative Filters** | 57 TOML filter definitions with block handlers | 108 TOML filter definitions with 25 declarative stages | **Condense** |
| **7. Streaming Architecture** | StreamFilter / BlockStreamFilter | EmissionSink / StageSession streaming pipeline | **Parity** |
| **8. Failure Safety** | Passthrough on non-zero exit codes & empty-file checks | 3-layer fail-open architecture with `PipelineExecutionException` abort to raw passthrough | **Parity** |
| **9. Testing Rigor** | 13 fixtures and inline `#[test]` assertions | 250+ dedicated test files with adversarial concurrency and corruption suites | **Condense** |
| **10. CI/CD & Automation** | Single release workflow | 6 multi-platform CI/CD workflows (Linux, macOS, Windows) | **Condense** |
| **11. Security & Trust** | Binary trust model | Granular capability grants, trust gates, and secret redactors | **Condense** |
| **12. MCP Support** | No Model Context Protocol server | Dedicated MCP server implementation (`mcp/`) | **Condense** |
| **13. Session Intelligence** | 120 KB command registry + 31 KB shell lexer & error pattern detector | Manifest discovery (`DiscoverService`), hook analyzer (`CompoundCommandAnalyzer`), and 5 offline transcript readers | **Zap** |
| **14. Analytics & Telemetry** | Gain tracking and telemetry reporting | Trend analysis, gap detection, and privacy-preserving preview hashes | **Condense** |
| **15. Performance** | Rust native compilation (<10ms startup) | GraalVM Native Image `--no-fallback` (<15ms startup) | **Parity** |
| **16. Hook Coverage** | 11 agent hooks supported | 11 agent hooks supported with integrity verification | **Parity** |
| **17. Platform Support** | Unix-first (macOS/Linux) | Dedicated Windows resolver (`PATHEXT`, PowerShell, cmd) and process tree reaping | **Condense** |

### Overall Evidence-Backed Scoreline

> **Condense: 9 wins | Parity: 7 ties | Zap: 1 win** (9–7–1)

Condense leads in ecosystem breadth, declarative filter count, testing rigor, Windows integration, security gates, and MCP tooling. Zap demonstrates a clear architectural win in arbitrary shell command stream parsing and failure pattern learning.

---

## Detailed Evaluation Across Key Architectural Areas

### 1. Structured Diagnostics IR vs. Text Filtering
- **Zap**: Implements a 3-tier degradation contract (`ParseResult<T>::Full`, `Degraded`, `Passthrough`) for typed results. When structured data is returned, it guarantees verified test results.
- **Condense**: Translates raw command stdout and stderr into typed Intermediate Representations (`Document`, `TestDocument`, `BuildDocument`, `DiagnosticDocument`, `GitDocument`). Structured IR is active across **25.0%** of the catalog (27 of 108 TOML filters), specifically covering the highest-traffic test and build runners (`jest`, `vitest`, `playwright`, `go test`, `cargo test`, `docker build`, `pytest`, `eslint`, and git operations). For remaining commands, Condense safely falls back to `Document.opaque`.
- **Verdict**: **Parity**. Both systems possess structured output capabilities; Zap enforces a formal 3-tier degradation model, while Condense provides a richer document taxonomy across its flagship runners.

### 2. Ecosystem & Tool Breadth
- **Zap**: Tracks 50+ tool families, with primary focus on Rust (`cargo`), JavaScript/TypeScript (`npm`, `yarn`, `pnpm`), and Git.
- **Condense**: Covers 140+ commands across 108 declarative TOML filter definitions, providing broad long-tail coverage including:
  - **.NET / C#**: MSBuild binary logs (`.binlog`), TRX test result files, and structured CLI diagnostics.
  - **Java / JVM**: Maven build failures, surefire XML reports, Gradle multi-project builds.
  - **Infrastructure as Code**: Terraform and OpenTofu plan JSON parsing and state listing.
  - **Python / Data Science**: Pytest frame summarization, Ruff, Mypy, and Pyright diagnostics.
- **Verdict**: **Condense wins** on breadth and declarative catalog volume.

### 3. Session Intelligence & Shell Analysis
- **Zap**: Features a dedicated **120 KB command registry** (`src/discover/registry.rs`), a **31 KB shell lexer** (`src/discover/lexer.rs`), and an error pattern detector (`src/learn/detector.rs`). Zap's lexer tokenizes command lines, quote styles, nested subshells, pipes, redirects, and control operators (`&&`, `||`, `;`), allowing deep analysis of live execution streams.
- **Condense**: Takes a complementary approach focused on workspace setup and privacy:
  - **Workspace Discovery**: `DiscoverService` scans project manifests (`package.json`, `pom.xml`, `Cargo.toml`) to recommend initial configuration.
  - **Hook Analysis**: `CompoundCommandAnalyzer` conservatively splits chained commands across control operators and strips wrapper commands (`sudo`, `env`).
  - **Transcript Readers**: 5 offline-first session readers parse local transcripts from Cursor, Claude Code, Windsurf, Antigravity, and Aider to estimate token savings without network transmission.
- **Verdict**: **Zap wins** on live shell syntax lexing and error pattern detection; Condense provides stronger offline transcript readers and project discovery.

### 4. Reliability & Fail-Open Safety Guarantee
- **Zap**: Reverts to raw uncompressed output on non-zero exit codes when filtering yields an empty result.
- **Condense**: Implements an immediate abort fail-open guarantee in `FilterPipeline`:
  - If any pipeline stage throws an unhandled exception or memory error (`PipelineExecutionException`, `StackOverflowError`), execution aborts immediately without applying subsequent lossy stages.
  - `PipelineBackedFilter` falls back to raw passthrough, combining stdout and stderr when a non-zero exit code indicates failure, and marks `wasFiltered = false` whenever failure incidents are recorded.
- **Verdict**: **Parity**. Both architectures guarantee that diagnostic evidence is not lost upon filter failure.

### 5. Platform Support & Windows Integration
- **Zap**: Optimized primarily for POSIX shells (bash, zsh) on macOS and Linux.
- **Condense**: Treats Windows as a first-class citizen alongside Linux and macOS:
  - Custom `WindowsCommandResolver` handles Windows-specific executable lookups (`.exe`, `.cmd`, `.bat`, `.ps1` via `PATHEXT`).
  - Reaps full child process process trees on timeout or abnormal termination, avoiding orphaned processes.
- **Verdict**: **Condense wins** on native multi-platform and Windows support.

---

## Reproducing the Verification Suite

To independently run the test and benchmark suites:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn test -Dtest=ReproducibleSuperiorityComparisonTest,RunnerIrCoverageTest,CompoundCommandSessionTest
```
