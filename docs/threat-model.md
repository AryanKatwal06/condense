# Condense Threat Model

**Version:** 1.0 (September 2026)  
**Status:** Active & CI-Enforced  
**Scope:** `condense` core CLI, MCP server, hook installer, structured parsers, configuration resolver, local persistence, and release distribution channels.

---

## 1. System Overview & Security Principles

Condense is a high-performance CLI proxy sitting between AI coding agents (or developers) and host shell processes to filter and compress output tokens by 60–90%.

### Core Security Principles

1. **Fail-Open for Child Truthfulness**: Condense never corrupts, replaces, or falsifies child process output, stdout/stderr streams, or exit codes. If an internal error occurs during proxying, Condense falls back to raw bytes or text and preserves the child's observable exit status.
2. **Fail-Closed for System Mutations**: When performing state-modifying actions (installing hooks, verifying updates, validating trust stores, parsing compound commands for auto-allowance), Condense fails closed: if input or state is ambiguous, it denies, prompts, or aborts rather than assuming safety.
3. **Zero Network Calls at Runtime**: Condense does not make outbound network requests during normal proxy execution, pricing estimation, or hook execution. Pricing catalogs and hook templates are compiled into the binary.
4. **Local-Only Data Storage**: Condense stores token statistics and execution timestamps locally in SQLite (`~/.local/share/condense/condense.db`). It stores zero command arguments, environment variables, stdout/stderr captures, or secrets.
5. **Least Privilege & Argv Preservation**: Child commands are executed with verbatim arguments via process exec APIs, never constructed through raw shell string concatenation (`sh -c`).

---

## 2. Trust Boundaries & Attack Vectors

```
  +-------------------------------------------------------------------------+
  |                             AI Coding Agent                             |
  |             (Claude Code, Cursor, Windsurf, Copilot, Cline, etc.)       |
  +-----------------------------------+-------------------------------------+
                                      |
                       Hook/MCP / CLI Invocation
                                      |
  +-----------------------------------v-------------------------------------+
  |                             Condense                                    |
  |                                                                         |
  |  [Boundary 2: Hooks]             [Boundary 3: MCP Server]               |
  |  - CompoundCommandAnalyzer       - McpServer / McpHandlers              |
  |  - HookInstaller                 - stdio transport                      |
  |                                                                         |
  |  [Boundary 5: Config & Trust]    [Boundary 1: Process Execution]        |
  |  - FilterOverrideResolver        - CommandExecutor / StreamingProxy     |
  |  - TrustStore                    - Argv preservation & stream pipes     |
  |                                                                         |
  |  [Boundary 4: Structured Parsers]                                       |
  |  - MsbuildBinlogStage, TrxReportStage, MachineUiStage                   |
  |                                                                         |
  |  [Boundary 6: Local Persistence]                                        |
  |  - DatabaseManager / SQLite (condense.db)                               |
  +-----------------------------------+-------------------------------------+
                                      |
                      Process Spawning & Filesystem
                                      |
  +-----------------------------------v-------------------------------------+
  |                       Operating System & Host Shell                     |
  |                 (Processes, Pipes, Filesystem, Temp Files)              |
  +-------------------------------------------------------------------------+
```

---

### Boundary 1: Child Process Execution Boundary

- **Components**: `CommandExecutor`, `StreamingProxy`, `WindowsCommandResolver`, `ExecutionResult`.
- **Threats**:
  - **Command Injection**: Maliciously crafted arguments intended to execute arbitrary code.
  - **PATH / Executable Hijacking**: Relative command names or PATHEXT shims resolving to untrusted executables in the working directory on Windows.
  - **Resource Exhaustion**: Child process emitting gigabytes of output or endless single lines causing `OutOfMemoryError`.
  - **Process Deadlocks**: Broken pipes or unconsumed stderr deadlocking the host.
- **Mitigations & Invariants**:
  - `ProcessBuilder` receives split argv lists directly; arguments are never interpolated into shell strings.
  - On Windows, `WindowsCommandResolver` resolves `.cmd`, `.bat`, and `.exe` files using the system `PATH` and `PATHEXT`, preventing working directory spoofing.
  - `CommandExecutor` enforces a strict 10 MiB stream capture cap and `Utf8LineDecoder` enforces a 1 MiB line cap.
  - Drain threads run concurrently with bounded buffers, ensuring pipes are drained even if one stream is idle.
- **Verifying Tests**: `CommandExecutorFailOpenTest`, `OutputCapAndTimeoutTest`, `Utf8LineDecoderBoundTest`, `NativeReliabilityIT`.

---

### Boundary 2: Hook System & Compound Command Boundary

- **Components**: `HookInstaller`, `CompoundCommandAnalyzer`, shell templates (`pre-tool-use.sh`, etc.).
- **Threats**:
  - **Interception / Permission Bypass**: Compound commands (e.g. `cat secret && condense pytest`) where dangerous commands slip through an auto-allow filter.
  - **Shell Syntax Ambiguity**: Nested subshells `$()`, backticks, heredocs, raw newlines, redirects hiding destructive commands.
  - **Hook Tampering**: Malicious scripts truncating or removing installed hook scripts mid-session.
- **Mitigations & Invariants**:
  - `CompoundCommandAnalyzer` uses a conservative finite-state lexer. It auto-allows a command if and only if **every** compound/pipeline segment matches an allowed pattern.
  - Any syntax ambiguity (unclosed quotes, subshells, backticks, unbalanced parentheses, raw newlines, redirects) defaults to `ask` / `deny`.
  - Hook scripts are installed with immutable permissions and verified for tampering/truncation prior to execution.
- **Verifying Tests**: `CompoundCommandAnalyzerTest`, `HookInstallerTest`, `NativeHookIT`.

---

### Boundary 3: MCP Server Boundary

- **Components**: `McpServer`, `McpHandlers`, `JsonRpcDispatcher`.
- **Threats**:
  - **JSON-RPC Injection**: Malicious JSON payloads with circular references or extreme nesting.
  - **Path Traversal via Resource Reads**: Requests for `condense://config` or file paths traversing outside permissible directories.
  - **Unauthorized Execution**: MCP tools executing arbitrary host commands without client consent.
- **Mitigations & Invariants**:
  - Transport is strictly bounded stdio; all messages parsed with Jackson streaming reader under bounded depth.
  - Resource URI resolvers validate paths using `SafePathValidator` and disallow symlink or directory escape.
  - MCP tools execute only through the standard `CommandExecutor` pipeline subject to the same capability ceilings as the CLI.
- **Verifying Tests**: `McpHandlersTest`, `McpConcurrentSessionBenchmarkTest`, `NativeMcpIT`.

---

### Boundary 4: Structured Parsers Boundary

- **Components**: `MsbuildBinlogStage`, `TrxReportStage`, `MachineUiStage`, `ResourceGraphStage`, `FormatReportStage`.
- **Threats**:
  - **XML External Entity (XXE) Injection**: Malicious TRX files embedding `<!ENTITY>` tags to read local files or trigger SSRF.
  - **XML Entity Expansion ("Billion Laughs")**: Exponential entity expansion causing CPU hang or heap exhaustion.
  - **Decompression Bombs**: Hostile MSBuild binlog `.binlog` files expanding into gigabytes of raw data.
  - **Hostile NDJSON / Deep Nesting**: Terraform machine UI or plan files with deeply nested structures designed to trigger `StackOverflowError`.
- **Mitigations & Invariants**:
  - All XML parsers (`DocumentBuilderFactory`, `XMLInputFactory`) explicitly disable `DOCTYPE`, external DTDs, and external entities (`disallow-doctype-decl = true`, `IS_SUPPORTING_EXTERNAL_ENTITIES = false`).
  - Gzip decompression for MSBuild binlog uses bounded counting streams capped at 50 MiB uncompressed limit.
  - Jackson streaming parsers enforce maximum nesting depth (1,000) and line limits, safely falling back to raw console text on parse failure.
- **Verifying Tests**: `StructuredParserBenchmarkTest`, `AdversarialSecurityTest`, `NativeDotnetIT`, `NativeTerraformIT`.

---

### Boundary 5: Configuration, Overrides & Trust Boundary

- **Components**: `FilterOverrideResolver`, `TrustStore`, `SafePathValidator`.
- **Threats**:
  - **Untrusted Repository Overrides**: A cloned malicious repo containing `.condense/filters.toml` that executes unauthorized commands or disables security filters.
  - **Capability Escalation**: User configs requesting forbidden pipeline capabilities (`exec`, arbitrary filesystem write).
  - **Path Traversal in Configs**: `include` or relative paths resolving outside workspace boundaries.
- **Mitigations & Invariants**:
  - Repository-level `.condense/filters.toml` is untrusted by default and must be explicitly approved via `TrustStore` cryptographic hash confirmation.
  - Declarative stages are bound by a static capability ceiling; stages cannot invoke external processes or shell commands.
  - `SafePathValidator` canonicalizes all paths, rejecting null bytes, `../` escapes, and symlink redirects.
- **Verifying Tests**: `FilterOverrideTest`, `TrustStoreTest`, `SafePathValidatorTest`, `NativeTrustIT`.

---

### Boundary 6: Local Persistence & Analytics Boundary

- **Components**: `DatabaseManager`, `SchemaMigrator`, `GainCommand`.
- **Threats**:
  - **SQL Injection**: Crafted command names or model identifiers injecting SQL syntax.
  - **Database Corruption / Lock Storms**: Concurrent native processes corrupting `condense.db`.
  - **Sensitive Data Leakage**: Accidental storage of command output, tokens, or environment keys in the local database.
- **Mitigations & Invariants**:
  - All database queries use parameterized `PreparedStatement`s exclusively; zero string concatenation in SQL queries.
  - Schema stores strictly: `(command_name, original_tokens, filtered_tokens, duration_ms, timestamp, model, estimator)`. Output text and arguments are never stored.
  - SQLite runs in WAL (Write-Ahead Logging) mode with `PRAGMA busy_timeout = 5000` and direct JDBC connection management. If database is corrupt, Condense fails open to proxying without writing.
- **Verifying Tests**: `DatabaseManagerTest`, `GainCommandCostTest`, `NativeConcurrencyStressIT`, `NativeAnalyticsFailOpenIT`.

---

### Boundary 7: Supply-Chain, Updates & Release Channels Boundary

- **Components**: `install.sh`, `install.ps1`, GitHub Actions release workflows, packaging manifests (`.deb`, `.spec`, `.rb`, `.json`, `.yaml`).
- **Threats**:
  - **Binary Tampering / MITM**: Downloading tampered binaries or corrupted archives during installation.
  - **Dependency Vulnerabilities**: Transitive CVEs in third-party libraries.
  - **Untracked Package Manifest Drift**: Packaging manifests advertising unbuilt architectures or broken install scripts.
  - **Supply-Chain Substitution**: Build-time injection of unreviewed dependencies.
- **Mitigations & Invariants**:
  - Every release artifact is checksummed in `checksums.txt` and signed using Sigstore/Cosign keyless signatures bound to GitHub Actions OIDC identity.
  - `install.sh` and `install.ps1` verify SHA-256 hashes against `checksums.txt` before placing binaries in user PATH. Checksum mismatches fail closed immediately with non-zero exit.
  - `RuntimeDependencyAllowlistTest` strictly locks the 5 allowable runtime Maven coordinates; new coordinates fail the build.
  - CycloneDX SBOM is published, validated, and signed with every release.
  - `PackagingManifestConsistencyTest` validates all package manifests for consistent versions, URLs, licenses, and supported target architectures.
- **Verifying Tests**: `RuntimeDependencyAllowlistTest`, `PackagingManifestConsistencyTest`, `ThirdPartyNoticeTest`, `LicensePolicyTest`.

---

## 3. Threat Matrix Summary

| Threat ID | Boundary | Threat Description | Severity | Mitigation Status | Verified By |
|---|---|---|---|---|---|
| **THREAT-01** | Process Exec | Command argument shell injection | Critical | Mitigated (verbatim argv split, zero shell exec) | `CommandExecutorFailOpenTest` |
| **THREAT-02** | Process Exec | Output stream memory exhaustion | High | Mitigated (10 MiB stream cap, 1 MiB line cap) | `OutputCapAndTimeoutTest`, `Utf8LineDecoderBoundTest` |
| **THREAT-03** | Hooks | Compound command auto-allow bypass | Critical | Mitigated (conservative finite-state analyzer) | `CompoundCommandAnalyzerTest` |
| **THREAT-04** | Hooks | Hook script tampering / deletion | High | Mitigated (idempotent installer, tamper check) | `NativeHookIT` |
| **THREAT-05** | Parsers | XML External Entity (XXE) attack in TRX | High | Mitigated (disallow-doctype-decl = true) | `AdversarialSecurityTest` |
| **THREAT-06** | Parsers | XML entity expansion bomb ("Billion Laughs") | High | Mitigated (entity expansion disabled) | `AdversarialSecurityTest` |
| **THREAT-07** | Parsers | Gzip decompression bomb in MSBuild binlog | High | Mitigated (bounded counting stream, 50MB cap) | `AdversarialSecurityTest` |
| **THREAT-08** | Config | Malicious untrusted `.condense/filters.toml` | High | Mitigated (cryptographic hash trust gating) | `TrustStoreTest`, `NativeTrustIT` |
| **THREAT-09** | Filesystem | Path traversal (`../../`, UNC, symlink escape)| High | Mitigated (`SafePathValidator` normalization) | `AdversarialSecurityTest` |
| **THREAT-10** | Database | SQL injection via command/model inputs | High | Mitigated (100% parameterized PreparedStatements)| `DatabaseManagerTest` |
| **THREAT-11** | Database | Database corruption locks proxy execution | Medium | Mitigated (fail-open SQLite architecture) | `NativeAnalyticsFailOpenIT` |
| **THREAT-12** | Supply-Chain | Malicious/corrupted release download | Critical | Mitigated (Cosign keyless signatures + SHA-256) | `install.sh`, `install.ps1`, `release.yml` |
| **THREAT-13** | Supply-Chain | Unreviewed runtime dependencies / CVEs | High | Mitigated (`RuntimeDependencyAllowlistTest`) | `RuntimeDependencyAllowlistTest` |
| **THREAT-14** | Packaging | Broken / dangling architecture manifests | Medium | Mitigated (manifest consistency verification) | `PackagingManifestConsistencyTest` |
