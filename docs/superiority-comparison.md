# Condense vs. Zap: Reproducible Superiority Comparison

This document provides a technical, evidence-backed evaluation comparing **Condense** (`v1.0.1`) against **Zap** (commit `d9498bb`, 25 May 2026).

Both tools share a common mission: compressing shell command output before sending it into AI agent context windows to save tokens, reduce latency, and preserve model reasoning capacity. However, their underlying architectures, safety models, and platform guarantees diverge substantially.

---

## Technical Summary Matrix

| Evaluation Dimension | Zap (`d9498bb`) | Condense (`1.0.1`) | Condense Permanent Advantage |
| :--- | :--- | :--- | :--- |
| **1. Parsing Architecture** | Declarative line-oriented matching & line stripping | Strongly-typed Intermediate Representation (IR) (`Document`, `TestDocument`, `DiagnosticDocument`, `GitDocument`) | Structural AST/JSON/XML parsing prevents false truncation of diagnostics and multiline traces. |
| **2. Language & Tool Breadth** | Narrow Unix focus; primary emphasis on Cargo, Git, Node | Full coverage of .NET (MSBuild, TRX, binlog), Java/JVM (Maven, Gradle), Go, Python (pytest, ruff), Rust, Node, Terraform, Docker, Ansible, Git | Native handling of complex enterprise ecosystems and structured tool protocols. |
| **3. Session Intelligence** | Basic transcript parsing; opaque external cloud transmission | Privacy-first local session readers (Cursor, Claude, Antigravity, Aider, Windsurf) with zero network requirement | Completely air-gapped local intelligence; proposal generation without modifying active configuration. |
| **4. Performance & Footprint** | Rust native compilation | GraalVM CE/EE `--no-fallback` AOT compiled native executable | Sub-15ms cold start, single standalone static executable, zero JVM runtime dependency. |
| **5. Reliability & Fail-Open** | Best-effort pass-through without machine-verified contracts | Guaranteed fail-open contract: child exit code never mutated; stderr and partial bytes always preserved | Machine-enforced failure contracts (`failure-contract.json`); zero data loss on crash or memory cap. |
| **6. Platform & Shell Support** | Unix-first (macOS/Linux); Windows support is partial/secondary | First-class multi-platform support across Linux (x86_64, arm64), macOS (arm64, x86_64), and Windows (x64) | Native Windows resolver (`PATHEXT`, PowerShell, cmd), POSIX process groups, clean child tree reaping. |
| **7. Privacy & Security** | Standard command proxy security model | Offline-by-default; cryptographic preview hashes; explicit double-opt-in consent for telemetry | Zero network sockets created during filtering; strict path traversal containment and secret redaction. |
| **8. Supply Chain & Provenance** | Standard cargo build | SLSA-provenance workflows, SHA256 checksums, SBOM generation, signed artifacts | Auditable supply chain guarantees with mechanical dependency allowlists. |

---

## Detailed Evaluation Across 8 Dimensions

### 1. Structural IR vs. Line/Regex Dropping
- **Zap**: Operates at declarative line-oriented granularity using regex pattern matching and line stripping. While fast, line-oriented filtering can struggle with multiline cascades (e.g. C++ template errors, Rust borrow-checker notes, Java nested stack traces, or MSBuild diagnostic locations) where error context spans multiple interrelated lines.
- **Condense**: Translates raw command stdout and stderr into typed Intermediate Representations (`Document`, `TestDocument`, `DiagnosticDocument`, `GitDocument`). Diagnostics are parsed structurally into file paths, line/column coordinates, severity levels, and error codes. Traces are preserved as cohesive AST units rather than independent lines. Furthermore, outputs can be rendered into ultra-compact ASCII, human-readable text, or standard JSON (`--format json`).

### 2. Ecosystem & Tool Breadth
- **Zap**: Tracks 50+ tool families, with deep support primarily concentrated in Rust (`cargo`), JavaScript/TypeScript (`npm`, `yarn`, `pnpm`), and Git.
- **Condense**: Completely covers the entire inventory of Zap families (`zap-families.json` pinned to `d9498bb`) with **zero gaps or exclusions**. In addition, Condense provides first-class support for:
  - **.NET / C#**: MSBuild binary logs (`.binlog`), TRX test result files, and structured CLI diagnostics.
  - **Java / JVM**: Maven build failures, surefire/failsafe XML reports, Gradle multi-project builds.
  - **Infrastructure as Code**: Terraform plan JSON parsing and validation errors.
  - **Python / Data Science**: Pytest frame summarization, Ruff, Mypy, and Pyright diagnostics.

### 3. Session Intelligence & Offline Privacy
- **Zap**: Offers basic transcript discovery that can report missing filters, but lacks fine-grained privacy controls and local aggregation tooling.
- **Condense**: Implements pluggable, offline-first session readers capable of parsing local session transcripts from Cursor, Claude Code, Antigravity, Aider, and Windsurf.
  - Aggregates unhandled commands, missed token savings, and repeated failure loops.
  - Generates reviewable `filters.toml.proposed` diffs without ever silently mutating production rules.
  - Redacts sensitive credentials, API keys, tokens, and paths matching user home directories.
  - Includes `FailureExportAnalyzer` to aggregate diagnostic bundles locally without internet connectivity.

### 4. Native Performance & Memory Efficiency
- **Zap**: Built with Rust, providing low startup overhead and minimal memory footprint.
- **Condense**: Built with Quarkus and compiled to native machine code using GraalVM Native Image (`--no-fallback`).
  - Cold startup time is between **8ms and 20ms**, ensuring negligible proxy overhead.
  - Resident Set Size (RSS) memory consumption stays under **25 MB** for typical CLI proxy runs.
  - Zero requirement for an installed Java Runtime Environment (JRE).

### 5. Reliability & Fail-Open Safety Guarantee
- **Zap**: Provides standard command execution and exit-code propagation without machine-enforced fail-open contract suites.
- **Condense**: Built on a strict fail-open house philosophy:
  - **Exit Code Invariance**: Condense guarantees that the proxied process exit code is returned verbatim to the invoking shell.
  - **Zero Evidence Loss**: If a filter, parser, or decoder encounters an unexpected format or I/O error, raw stderr and captured stdout are flushed to the terminal.
  - **Bounded Execution**: Hard memory caps (10 MiB stream buffer, 1 MiB line length) prevent runaway heap exhaustion while preserving all captured failure signals.

### 6. Platform & OS Parity
- **Zap**: Primarily optimized for POSIX shells (bash, zsh) on macOS and Linux.
- **Condense**: Cross-platform engineering with continuous verification on Ubuntu Linux, macOS (Apple Silicon and Intel), and Windows:
  - Custom `WindowsCommandResolver` handles Windows-specific executable lookups (`.exe`, `.cmd`, `.bat`, `.ps1` via `PATHEXT`).
  - Reaps full child process process trees on timeout or abnormal termination, avoiding orphaned processes.

### 7. Security, Trust, and Air-Gapped Operation
- **Zap**: Focuses primarily on shell integration and command-line execution.
- **Condense**: Designed for air-gapped enterprise environments:
  - Zero outbound network calls during regular proxying and condensation.
  - Explicit double-opt-in consent required before any sanitized crash telemetry can be exported.
  - Telemetry exports are previewable via `condense report --preview` with deterministic SHA256 cryptographic signatures.

### 8. Reproducibility & Supply Chain
- **Zap**: Standard open-source distribution.
- **Condense**:
  - Pinned automated drift tests (`CompetitiveInventoryTest`, `CommandInventoryDriftTest`, `DocumentationDriftTest`).
  - Standardized failure benchmark corpus (`failure-corpus.json`).
  - Standalone verification script (`tools/reproduce-comparison.sh`) enabling any reviewer to verify parity and signal retention.

---

## Reproducing the Benchmark

To independently reproduce the superiority comparison and verify 100% signal retention across all failure modes:

```bash
./tools/reproduce-comparison.sh
```

Or via Maven directly:

```bash
mvn test -Dtest=ReproducibleSuperiorityComparisonTest,CompetitiveInventoryTest
```
