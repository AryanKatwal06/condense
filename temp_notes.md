## Condense v1.0.1

Condense is a CLI proxy that reduces AI coding agent token usage by
60-92% by filtering noisy command output before the AI sees it.

Run `condense init -g` after installing to hook it into Claude Code,
Cursor, Copilot, Cline, Gemini CLI, or Windsurf.

### Installation

**Linux / macOS:**
```bash
curl -fsSL https://github.com/AryanKatwal06/code-condenser/releases/latest/download/install.sh | bash
```

**Windows:**
```powershell
irm https://github.com/AryanKatwal06/code-condenser/releases/latest/download/install.ps1 | iex
```

Then run `condense --version` to confirm the install.

### Verify the download

All binaries are signed with Sigstore/cosign. SHA-256 checksums are in
`checksums.txt`. See the README for the full verification command.

### What changed in v1.0.1

Post-launch fixes: Apache 2.0 license files, repository cleanup, macOS
runner pinned to macOS 15, Windsurf hook documentation, Gemini CLI
idempotency fix.

For the full list of supported commands and AI tool integrations, see
the [README](https://github.com/AryanKatwal06/code-condenser#readme).
