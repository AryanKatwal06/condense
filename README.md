# Condense

[![Release](https://img.shields.io/github/v/release/AryanKatwal06/condense)](https://github.com/AryanKatwal06/condense/releases/latest)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
[![Build](https://github.com/AryanKatwal06/condense/actions/workflows/build.yml/badge.svg)](https://github.com/AryanKatwal06/condense/actions/workflows/build.yml)

**Condense** sits between your AI coding assistant and the shell. It filters command output so the AI sees a compact summary instead of thousands of raw lines — typically cutting estimated context-window tokens by 60–92%. Those percentages are estimates (`utf8_weighted_v1`, p95 ±35% vs cl100k_base); see [docs/token-estimator.md](docs/token-estimator.md).

---

## The Problem

When an AI coding agent runs `pytest`, `cargo test`, `git diff`, or `kubectl pods`, it receives thousands of lines of raw output. This consumes massive context window tokens even when 95% of that output is irrelevant noise (passing tests, redundant file paths, ANSI escape codes, progress bars, etc.). This leads to slower response times, degraded AI reasoning (due to "lost in the middle" effects), and high API costs.

Condense solves this. It sits between the AI agent and the shell, intercepts command output, applies command-specific compression logic, and returns only the signal the AI actually needs. For example, a failed `pytest` run that produces 2,400 lines of output becomes 8 lines showing only the failed tests and summary. In production analytics, across 513 commands recorded, an estimated 960,552 input tokens were reduced to 73,705 output tokens — a **92% savings**. Token figures from `condense gain` are estimates, not tokenizer-grade counts ([docs/token-estimator.md](docs/token-estimator.md)).

## How It Works

Here's an example of `pytest` output:

**Without condense** (2000+ lines of raw output):
```text
============================= test session starts ==============================
platform linux -- Python 3.10.12, pytest-7.4.0, pluggy-1.0.0
rootdir: /workspace/project
collected 412 items

tests/test_auth.py .................................................... [ 12%]
tests/test_api.py ......................................F.............. [ 25%]
tests/test_models.py .................................................. [ 38%]
... (1800 more lines) ...
=========================== short test summary info ============================
FAILED tests/test_api.py::test_login_timeout - TimeoutError
=================== 1 failed, 411 passed in 12.45s =====================
```

**With condense** (compressed to 6 lines):
```text
pytest failed (exit code 1)
Failed Tests:
- tests/test_api.py::test_login_timeout
  Error: TimeoutError
Summary: 1 failed, 411 passed in 12.45s
```

**The Mechanism:**
1. The AI agent runs `condense pytest` instead of `pytest`
2. Condense executes the real command and captures its full output
3. Condense applies pytest-specific filtering and returns only the failures and summary to the AI

---

## Installation

### Linux (x64 and aarch64)
```bash
curl -fsSL https://github.com/AryanKatwal06/condense/releases/latest/download/install.sh | bash
```
Then run `condense --version` to verify.

### macOS (Apple Silicon)
```bash
curl -fsSL https://github.com/AryanKatwal06/condense/releases/latest/download/install.sh | bash
```
Then run `condense --version` to verify. *(Note: Intel macOS binaries are not pre-built; see [Building from Source](#building-from-source).)*

### Windows (x64)
```powershell
irm https://github.com/AryanKatwal06/condense/releases/latest/download/install.ps1 | iex
```
Then run `condense --version` to verify.

### Manual Installation
You can download the binary for your platform directly from the [GitHub Releases](https://github.com/AryanKatwal06/condense/releases) page. Make it executable (`chmod +x` on Linux/macOS) and place it on your PATH (e.g., `~/.local/bin/` on Linux/macOS, or any directory on your PATH on Windows). Then run `condense --version` to verify. Note that the install script requires internet access to download from GitHub Releases.

---

## Quick Start

1. **Verify installation**: `condense --version`
2. **Try it manually**: `condense git status` (see the compressed output yourself)
3. **View your savings**: `condense gain` (shows your token analytics dashboard)
4. **Set up AI tool integration**: `condense init -g` (installs hooks into your AI coding assistant so it automatically uses condense without changing commands)
5. **Configure exclusions** (optional): Open `~/.config/condense/config.toml` (Linux), `~/Library/Application Support/condense/config.toml` (macOS), or `%APPDATA%\condense\config.toml` (Windows) and add commands to `exclude_commands` — for example `exclude_commands = ["make", "cat"]` to pass those through unfiltered.

---

## Supported Commands

For any unrecognized command, condense passes output through unchanged — it is always safe to prefix any command with `condense`.

| Command | What gets filtered | Typical reduction |
|---|---|---|
| `git status` | Unchanged files, branch details | ~70% |
| `git diff` | Context lines, metadata | ~60% |
| `git log` | Full commit bodies, author details | ~75% |
| `git push` | Remote tracking info | ~65% |
| `git commit` | Pre-commit hook noise, staged details | ~60% |
| `git add` | Ignored files, verbose flags | ~50% |
| `cargo test` | Passing tests, compilation output | ~85% |
| `cargo build` | Verbose build steps | ~70% |
| `cargo clippy` | Warning grouping | ~80% |
| `cargo install` | Download progress, verbose logs | ~90% |
| `pytest` | Passing tests, setup output | ~90% |
| `python -m pytest` | Passing tests, setup output | ~90% |
| `go test` | Passing test events | ~85% |
| `jest` | Passing tests, coverage | ~88% |
| `vitest` | Passing tests | ~87% |
| `tsc` | Successful compilations | ~75% |
| `eslint` | Grouped by rule | ~80% |
| `ruff check` | Grouped violations | ~82% |
| `golangci-lint run` | Grouped warnings | ~80% |
| `docker ps` | Verbose container details | ~65% |
| `docker build` | Step-by-step layer noise | ~80% |
| `docker logs` | Timestamp noise, repetitive lines | ~50% |
| `kubectl` (pods/describe) | Verbose status fields | ~70% |
| `aws ec2 describe-instances` | JSON structure compression | ~85% |
| `ls` | Long listing details | ~60% |
| `find` | Full path verbosity | ~55% |
| `grep` / `rg` | Context lines | ~50% |
| `cat` | Truncates extremely large files | ~40% |
| `make` | Build lifecycle noise | ~75% |
| `mvn` / `gradle` | Build lifecycle noise, downloads | ~80% |
| `npm install` | Dependency resolution noise | ~85% |
| `pip install` | Download progress bars | ~90% |

---

## AI Tool Integration

Running `condense init -g` installs hooks into your AI coding assistant. This means every shell command your AI runs is automatically intercepted and filtered by condense — the AI never has to know condense exists.

| Tool | Hook mechanism | Status |
|---|---|---|
| Claude Code | PreToolUse JSON stdin/stdout — silently rewrites command | ✅ Supported |
| Cursor | beforeShellExecution — denies and suggests condense prefix | ✅ Supported |
| GitHub Copilot CLI | preToolUse deny/allow — cross-platform Bash + PowerShell | ✅ Supported |
| Gemini CLI | BeforeTool deny/redirect — requires paid API key (free tier ended Jun 2026) | ✅ Supported |
| Cline | PreToolUse executable script — macOS/Linux only (Cline doesn't support Windows hooks) | ✅ Supported |
| Windsurf | pre_run_command exit-code hook — Beta; auto-retry behavior unconfirmed | ⚠️ Beta |

`condense init --remove` removes all hooks. `condense init --tool <tool>` installs a hook for a specific tool only.

To verify hooks are working, run a few commands via your AI and then run `condense gain` to see the recorded commands. For full details and troubleshooting, see [HOOKS.md](docs/HOOKS.md).

---

## How Condense Interacts with Other Token-Saving Tools

AI agents increasingly ship with their own context-saving mechanisms. Condense is designed to compose cleanly with them:

*   **Claude Desktop (MCP)**: An MCP server mode is planned — this would let Claude Desktop use condense natively without hook installation. Not yet available in v1.0.x.
*   **Claude Code (Compact Mode)**: Claude Code strips some whitespace automatically. Condense runs *first*, stripping entire irrelevant blocks (like passing tests), and then Claude compacts what's left. They stack multiplicatively.
*   **Aider (Repo Map)**: Aider uses ctags to map codebases. Condense doesn't interfere with this; it focuses purely on transient shell output, which Aider's map doesn't cover.

---

## Ultra-Compact Mode

If your AI is struggling with context limits, you can enable ultra-compact mode. This trades human readability for maximum token efficiency.

```toml
# ~/.config/condense/config.toml
[general]
ultra_compact = true
```

When enabled, condense will strip indentation, remove all decorative characters (like `-`, `=`, `*`), and flatten nested structures. A 20-line error report might become 3 lines of dense, comma-separated facts. AI models parse this perfectly, but humans will find it hard to read.

---

## Performance

Condense is built as a GraalVM native image for instant startup.

| Platform | Startup | Binary size |
|---|---|---|
| Linux x64 | 6 ms | 52 MB |
| macOS arm64 (Apple Silicon) | 43–58 ms | 50 MB |
| Windows x64 | 34–54 ms | 52 MB |

*(Note: "Cold start" is measured on each new command invocation since condense is a per-command proxy, not a persistent daemon. Linux performance is notably consistent due to better I/O characteristics on GitHub's Linux runners.)*

---

## Privacy

**condense collects zero telemetry.** All analytics data (`condense gain`) is stored locally in a SQLite database at:
- Linux: `~/.local/share/condense/condense.db`
- macOS: `~/Library/Application Support/condense/condense.db`
- Windows: `%APPDATA%\condense\condense.db`

condense makes no network calls during normal operation. The only exception is the optional `condense update` command, which contacts GitHub Releases when explicitly invoked by you. condense never phones home, tracks usage, or sends any data anywhere.

---

## condense gain (Analytics)

`condense gain` gives you a detailed breakdown of estimated token savings. Counts are produced by `utf8_weighted_v1` (p95 relative error 35% vs cl100k_base). The JSON form includes an `estimator` object; existing fields are unchanged.

```bash
$ condense gain --graph

Daily Token Savings — Last 30 Days
────────────────────────────────────────────────────────────

 192k |                                                                      ## ##               
 168k |                                                                      ## ##               
 144k |                                                                      ## ##               
 120k |                                                                      ## ##               
  96k |                                                                      ## ##          ##   
  72k |                                                                      ## ##          ## ##
  48k |                                                                      ## ##          ## ##
  24k |                                                                      ## ##          ## ##
       ──────────────────────────────────────────────────────────────────────────────────────────
       Jun 6                Jun 13               Jun 20               Jun 27               Jul 4

$ condense gain --top 10
Top Commands by Tokens Saved:
1. pytest (420,500 saved)
2. cargo build (210,000 saved)
3. git status (115,000 saved)
...
```
Other flags include `--daily`, `--weekly`, `--top 10`, `--scope project`, `--since 7`, and `--format json`.

---

## Configuration

Condense can be configured via a TOML file.
- Linux: `~/.config/condense/config.toml`
- macOS: `~/Library/Application Support/condense/config.toml`
- Windows: `%APPDATA%\condense\config.toml`

Example configuration:
```toml
# General settings
[general]

[hooks]
# Commands to exclude from hook interception (passed through directly)
exclude_commands = []

[tee]
# Save raw output on command failure for AI inspection
# Options: "failures" | "always" | "never"
mode = "failures"

[commands.pytest]
max_failures = 10

[commands.cargo-test]
show_timing = false
```

---

## Updating

Run `condense update` to automatically check GitHub Releases, download the correct platform binary, verify its SHA-256 checksum, and atomically replace the current binary. On success, it prints:
> Successfully updated to vX.Y.Z! The new version will be used the next time you run condense.

---

## Troubleshooting

### Persistence Failure Warning in `condense gain`
If you run `condense gain` and see the warning:
```text
⚠ analytics unavailable — persistence failed, see logs
```
This indicates that condense encountered an error when attempting to write to or read from its local SQLite analytics database (`condense.db`).

**Impact:**
- **Zero impact on filtering:** Condense is designed to fail open. Core command compression and proxying continue to work normally even if analytics tracking encounters a persistence error.

**Common Causes & Resolution:**
1. **Directory or File Permissions:** Verify that your user account has read and write permissions to the database directory:
   - **Linux:** `~/.local/share/condense/` or `~/.config/condense/`
   - **macOS:** `~/Library/Application Support/condense/`
   - **Windows:** `%APPDATA%\condense\`
2. **Corrupted Database File:** If the database file was damaged by an unexpected termination or disk issue, you can remove or rename `condense.db`. Condense will automatically initialize a fresh database and schema on the next invocation.
3. **Outdated Binary:** If you are running an older release or self-built binary, ensure you update to the latest release (`condense update`) where native SQLite driver initialization is resolved.

---

## Building from Source

Prerequisites: GraalVM JDK 21 (Mandrel 23.1+ recommended), Maven 3.9+

```bash
git clone https://github.com/AryanKatwal06/condense.git
cd condense/condense
./mvnw test                        # run the JVM test suite
./mvnw package -Pnative            # build native binary
./target/condense-runner --version # verify
```

---

## Contributing

Pull requests are open. New compressing filters need a `corpus/catalog.json` row that meets the 60% estimated-savings floor (or an enumerated exemption) and 100% critical-signal retention. CI enforces both on JVM; `NativeCorpusIT` smokes one catalog entry through the native binary. See [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/fidelity-corpus.md](docs/fidelity-corpus.md).

---

## License & Attribution

Condense is licensed under the Apache License 2.0. See [LICENSE](LICENSE) for details.
