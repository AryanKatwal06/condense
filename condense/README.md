# Condense

> Filter AI command output. Save 60–90% of tokens. Run faster, spend less.

Condense is a CLI proxy that sits between your AI coding assistant and the shell. When
your AI runs `git status`, it gets hundreds of lines of raw output. With Condense, it
gets a compact summary: `[main] staged: 2 | modified: 1 | untracked: 3`.

**Java + GraalVM port** of [bitan-del/condense](https://github.com/bitan-del/condense).

---

## Install

### Linux and macOS (Shell Script)
```bash
curl -fsSL https://raw.githubusercontent.com/YOUR_ORG/condense/main/install.sh | sh
```

### Windows (PowerShell)
```powershell
iwr https://raw.githubusercontent.com/YOUR_ORG/condense/main/install.ps1 -useb | iex
```

Or download a binary directly from the [Releases](https://github.com/YOUR_ORG/condense/releases) page.

**Verified platforms**: 
- **Linux x64**: Shell script install, manual download
- **macOS (Apple Silicon & Intel)**: Shell script install, manual download 
- **Windows x64**: PowerShell script install, manual download

### Verify checksum (recommended)

```bash
curl -LO https://github.com/YOUR_ORG/condense/releases/download/v0.1.0/condense-linux-x64
curl -LO https://github.com/YOUR_ORG/condense/releases/download/v0.1.0/condense-linux-x64.sha256
echo "$(cat condense-linux-x64.sha256)  condense-linux-x64" | sha256sum --check
chmod +x condense-linux-x64 && mv condense-linux-x64 ~/.local/bin/condense
```

---

## Quick Start

```bash
# Without Condense: AI sees 40+ lines of raw git output
git status

# With Condense: AI sees one line
condense git status
# → [main] staged: 2 | modified: 1 | untracked: 3

# Ultra-compact mode (-u)
condense -u git status
# → [main] ↑S:2 M:1 ?:3

# Verbose mode (-vv): summary + file list
condense -vv git status
```

---

## Supported Commands

| Category | Commands |
|---|---|
| **Git** | `git status`, `git diff`, `git log`, `git push`, `git commit`, `git add` |
| **Rust / Cargo** | `cargo test`, `cargo clippy`, `cargo build`, `cargo install` |
| **Python** | `pytest`, `ruff`, `pip install`, `python -m pytest` |
| **Go** | `go test`, `golangci-lint run` |
| **Node** | `npm install`, `jest`, `vitest`, `eslint`, `tsc` |
| **Cloud** | `docker ps`, `docker build`, `docker logs`, `kubectl`, `aws` |
| **File system** | `ls`, `find`, `grep`, `rg`, `cat` |
| **Build tools** | `make`, `mvn`, `gradle` |

---

## Install AI Tool Hooks

Install hooks so your AI coding tools automatically use Condense without typing `condense` prefix:

```bash
# Install hooks for all supported tools
condense init -g

# Check which hooks are installed
condense init --show

# Remove all hooks
condense init --remove
```

**Supported tools**: Claude Code, Cursor, Gemini CLI, Windsurf, GitHub Copilot, Cline

> **Note for Claude Code users**: Claude Code handles multiple hooks rewriting the same command in parallel with no guaranteed order. If you have competing `PreToolUse` hooks for Bash, condense's interception may not always take effect.

---

## Token Savings Dashboard

```bash
# Summary panel
condense gain

# 30-day bar chart
condense gain --graph

# Recent command history with savings %
condense gain --history 20

# Top commands by tokens saved
condense gain --top 10

# Project-specific stats
condense gain --scope project

# Machine-readable JSON
condense gain --format json
```

---

## Privacy & Telemetry

**Privacy Statement: Condense collects ZERO telemetry.** 

All analytics data (`condense gain`) is stored entirely locally in a SQLite database located at your system's standard data directory (e.g., `~/.local/share/condense/condense.db` on Linux). This data is **never** transmitted anywhere.

Condense makes **no network calls** during normal operation. The *only* exception is if you explicitly run `condense update`, which securely contacts the GitHub API to check for updates and downloads the new binary if one is available.

---

## Configuration

```bash
# View all settings
condense config --list

# Set a value
condense config --set tee.mode=always

# Get a value
condense config --get tee.mode

# Reset to defaults
condense config --reset
```

Config file: `~/.config/condense/config.toml` (Linux) · `~/Library/Application Support/condense/config.toml` (macOS)

---

## Shell Completion

Condense can generate completion scripts for Bash, Zsh, and Fish.

### Bash
```bash
condense completion bash > ~/.local/share/bash-completion/completions/condense
```

### Zsh
```bash
condense completion zsh > ~/.zfunc/_condense
# Ensure ~/.zfunc is in your fpath in ~/.zshrc:
# fpath=(~/.zfunc $fpath)
# autoload -Uz compinit && compinit
```

### Fish
```bash
condense completion fish > ~/.config/fish/completions/condense.fish
```

---

## Build from Source

**Prerequisites**: GraalVM JDK 21 with `native-image` on PATH, Maven 3.9+

```bash
git clone https://github.com/YOUR_ORG/condense.git
cd condense

# JVM mode (fast iteration)
mvn verify

# Native binary (~50ms startup)
mvn package -Pnative -DskipTests
./target/condense-runner --version
```

For static Linux binaries (runs on any distro):
```bash
sudo apt-get install musl-tools
mvn package -Pnative -DskipTests \
  -Dquarkus.native.additional-build-args="--static,--libc=musl"
```

---

## Project Structure

```
src/main/java/com/condenseproxy/
├── CondenseMain.java                   — Quarkus entry point
├── CondenseRootCommand.java            — Root picocli command + passthrough dispatch
├── VersionProvider.java           — Version from version.properties
├── annotation/
│   ├── CommandFilter.java         — CDI qualifier for filter registration
│   └── CommandFilters.java        — Repeatable container
├── core/
│   ├── CommandExecutor.java       — Child process execution (deadlock-safe)
│   ├── ConfigLoader.java          — TOML config loading
│   ├── ExecutionResult.java       — Process output record
│   ├── FilterResult.java          — Filter output record
│   ├── FilterStrategy.java        — Filter interface
│   ├── PassthroughStrategy.java   — Default (no-op) filter
│   ├── PlatformDirs.java          — XDG/macOS/Windows path resolution
│   ├── ProjectFingerprint.java    — SHA-256 project directory fingerprint
│   ├── StrategyRegistry.java      — CDI-based filter discovery + dispatch
│   ├── TeeMode.java               — Tee mode enum
│   ├── TeeWriter.java             — Raw output dump system
│   ├── TokenCounter.java          — Token estimation utility
│   ├── TrackingRepository.java    — SQLite analytics write + query
│   └── CondenseConfig.java             — Config record
├── filter/
│   ├── git/                       — GitStatus, GitDiff, GitLog, GitPush, GitCommit, GitAdd
│   ├── cargo/                     — CargoTest, CargoClippy, CargoInstall
│   ├── python/                    — Pytest, Ruff, PipInstall, Python
│   ├── golang/                    — GoTest, GolangciLint
│   ├── node/                      — Jest, Vitest, ESLint, Tsc, NpmInstall
│   ├── fs/                        — Ls, Find, Grep, Cat
│   ├── cloud/                     — DockerPs, DockerBuild, Docker, Kubectl, Aws
│   ├── build/                     — Make, Mvn, Gradle
│   └── strategy/                  — Deduplication, Grouping, AnsiStrip, JsonStructure, TreeCompression, StateMachine
├── analytics/
│   ├── AsciiGraphRenderer.java    — All terminal rendering
│   ├── GainCommand.java           — condense gain subcommand
│   ├── GainReport.java            — JSON-serializable report record
│   └── GainRepository.java        — Query orchestration
├── hooks/
│   ├── HookInstaller.java         — Install/show/remove hooks
│   ├── HookTemplate.java          — Template loading + sentinel detection
│   ├── HookTool.java              — Supported AI tool enum
│   └── InitCommand.java           — condense init subcommand
└── config/
    ├── ConfigCommand.java         — condense config subcommand
    └── ConfigWriter.java          — Atomic TOML write-back
```

---

## Performance

| Metric | Value |
|---|---|
| Cold start | ~50ms (Linux), ~100ms (macOS) |
| Filter overhead | 2–15ms |
| Memory (RSS) | ~25MB |
| Binary size | ~35–50MB (static Linux), ~45MB (macOS) |

Linux binaries are fully static (musl) — no glibc dependency, runs on any distro.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to add new command filters and the
full development guide.

## Security

See [SECURITY.md](SECURITY.md) for the security policy and how to report vulnerabilities.

## License

[Apache License 2.0](LICENSE)

Original Rust implementation: [bitan-del/condense](https://github.com/bitan-del/condense)
