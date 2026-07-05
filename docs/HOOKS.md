# Condense Hooks Documentation

Condense integrates with AI coding assistants by installing "hooks" that automatically intercept shell commands and route them through `condense`. This means the AI never has to know condense exists; it simply runs `pytest`, and the hook silently rewrites it to `condense pytest` (or rejects it and tells the AI to retry, depending on the tool's hook capabilities).

This document explains exactly how each hook works mechanically, where files are placed, and how to troubleshoot.

---

## Tool Hooks Reference

### Claude Code

- **Mechanism**: `PreToolUse` (JSON stdin/stdout). Claude Code passes the planned tool invocation as JSON via stdin to the hook. Condense's hook script parses this, identifies supported commands (like `git`, `cargo`, `pytest`), modifies the `command` field to prepend `condense `, and writes the modified JSON to stdout. This transparently rewrites the command before it runs.
- **Locations**:
  - **macOS/Linux**:
    - Config: `~/.claude/settings.json` (adds a `Bash` matcher entry under `PreToolUse`)
    - Script: `~/.claude/hooks/condense-hook.sh`
  - **Windows**:
    - Config: `%USERPROFILE%\.claude\settings.json`
    - Script: `%USERPROFILE%\.claude\hooks\condense-hook.ps1`
- **What gets installed**: A shell script that reads JSON from `stdin`, checks if `tool_name == "Bash"`, extracts the command, and if it matches `CONDENSE_COMMANDS`, returns a JSON object allowing the command but modifying the `tool_input.command`.
- **Verification**: 
  - Manual: `echo '{"tool_name": "Bash", "tool_input": {"command": "git status"}}' | ~/.claude/hooks/condense-hook.sh`
  - Live: Open Claude Code, ask it to run `git status`. You will see it run `condense git status`. Run `condense gain` afterward to verify the tokens were saved.
- **Caveats**: Claude Code resolves multiple `PreToolUse` hooks in parallel. If you have competing Bash hooks that also attempt to rewrite the command, the composition order is undefined and condense may not reliably intercept.
- **Manual Removal**: Remove the `condense-hook.sh` entry from `~/.claude/settings.json` and delete the script.

### Cursor

- **Mechanism**: `beforeShellExecution` (deny + redirect). Cursor's hook API doesn't support silent command rewriting. Instead, the condense hook script inspects the command, and if it's a supported command run without `condense`, it exits with code 1 (via JSON) and prints an error message instructing the AI: "Use 'condense <command>' instead to get filtered, token-efficient output." The AI sees this failure and automatically retries with the `condense` prefix.
- **Locations**:
  - **macOS/Linux**:
    - Config: `~/.cursor/hooks.json`
    - Script: `~/.cursor/hooks/condense-hook.sh`
  - **Windows**:
    - Config: `%USERPROFILE%\.cursor\hooks.json`
    - Script: `%USERPROFILE%\.cursor\hooks\condense-hook.ps1`
- **What gets installed**: A script that intercepts `beforeShellExecution`. If the bare command matches our list, it responds with `{"continue": false, "permission": "deny", "agentMessage": "..."}`.
- **Verification**: 
  - Manual: `echo '{"command": "npm install"}' | ~/.cursor/hooks/condense-hook.sh`
  - Live: In a Cursor terminal, ask it to run `npm install`. You will briefly see it fail the raw command and immediately retry with `condense npm install`.
- **Caveats**: Cursor resolves multiple `beforeShellExecution` hooks in parallel. If another hook modifies commands, condense's interception may not reliably take effect.

### GitHub Copilot CLI

- **Mechanism**: `preToolUse` (deny + allow). Similar to Cursor, the script intercepts and rejects noisy commands, prompting Copilot to retry. Copilot's CLI architecture supports this seamlessly across platforms.
- **Locations**:
  - **macOS/Linux**:
    - Config: `~/.copilot/hooks/condense-hooks.json`
    - Script: `~/.copilot/hooks/condense-hook.sh`
  - **Windows**:
    - Config: `%USERPROFILE%\.copilot\hooks\condense-hooks.json`
    - Script: `%USERPROFILE%\.copilot\hooks\condense-hook.ps1`
- **What gets installed**: A script checking if `toolName == "bash"` (or PowerShell). Parses `toolArgs`, checks command, returns `{"permissionDecision": "deny"}` if it matches.
- **Verification**: 
  - Manual: `echo '{"toolName": "bash", "toolArgs": "{\"command\": \"make test\"}"}' | ~/.copilot/hooks/condense-hook.sh`
  - Live: Run a shell task via Copilot CLI. Check `condense gain` to confirm the command was recorded.

### Gemini CLI

- **Mechanism**: `BeforeTool` (deny + redirect). Hook intercepts `run_shell_command` tool invocations. If a supported command is detected, it fails the request with instructions to retry using condense.
- **Locations**:
  - **macOS/Linux**:
    - Config: `~/.gemini/settings.json`
    - Script: `~/.gemini/hooks/condense-hook.sh`
  - **Windows**:
    - Config: `%USERPROFILE%\.gemini\settings.json`
    - Script: `%USERPROFILE%\.gemini\hooks\condense-hook.ps1`
- **What gets installed**: A python-powered shell script to safely parse Gemini's tool JSON and gracefully deny commands. Note that any stdout outside of the JSON response breaks Gemini's parser, so the script enforces strict stdout silence.
- **Verification**: Ask Gemini to run a test suite. It will retry using condense.
- **Caveats**: **Requires a paid API key.** As of June 2026, the free tier for Gemini CLI has been deprecated and does not support tool hooks.

### Windsurf

- **Mechanism**: `pre_run_command` (exit code 2). Windsurf uses a cascade hook system. Condense exits with code 2 to signal a redirect, printing the suggested command.
- **Locations**:
  - **macOS/Linux**:
    - Config: `~/.codeium/windsurf/hooks.json`
    - Script: `~/.codeium/windsurf/hooks/condense-hook.sh`
  - **Windows**:
    - Config: `%USERPROFILE%\.codeium\windsurf\hooks.json`
    - Script: `%USERPROFILE%\.codeium\windsurf\hooks\condense-hook.ps1`
- **What gets installed**: A script that exits `2` for matching commands.
- **Verification**: Check `hooks.json` to ensure the `pre_run_command` entry was added.
- **Caveats**: **Beta feature.** Windsurf's Cascade Hooks are currently in beta. Whether Cascade automatically retries with the suggested `condense <command>` invocation depends on Windsurf's internal behavior and has not been fully confirmed to work 100% of the time.

### Cline

- **Mechanism**: `PreToolUse` (executable script). Cline uses a single executable script for pre-tool interception. The condense installer deploys a script that parses the JSON payload, checks for the `execute_command` tool, and if it matches a noisy command, it cancels the tool use and returns an error instructing Cline to retry with condense.
- **Locations**:
  - **macOS/Linux**:
    - Script: `~/Documents/Cline/Rules/Hooks/PreToolUse`
- **What gets installed**: A script interpreting Cline's specific `execute_command` arguments and returning `{"cancel": true, "errorMessage": "..."}`.
- **Verification**: 
  - Manual: `echo '{"preToolUse": {"toolName": "execute_command", "parameters": {"command": "git status"}}}' | ~/Documents/Cline/Rules/Hooks/PreToolUse`
  - Live: Have Cline execute a shell command like `cargo check`.
- **Caveats**: **macOS/Linux only.** Cline does not currently support hooks on Windows. Furthermore, Cline only supports a *single* `PreToolUse` script. If you already have one, Condense will refuse to overwrite it and will print instructions on how to manually merge the logic.

---

## Configuration

Condense hooks can be customized via `~/.config/condense/config.toml` (or `%APPDATA%\condense\config.toml` on Windows). 

```toml
[hooks]
# If you want to stop intercepting specific commands, add them here
exclude_commands = ["git", "make"]
```

The hook scripts read this configuration on every invocation, so changes take effect immediately without requiring a restart or `condense init` rerun.

### Ultra-Compact Mode

If your AI is struggling with context limits, you can enable ultra-compact mode in the configuration file:

```toml
[general]
ultra_compact = true
```

When enabled, condense trades human readability for maximum token efficiency by stripping indentation, decorative characters, and flattening structures.

---

## Troubleshooting

### "condense gain shows 0 commands recorded"
This means condense isn't being run, or it cannot initialize its local SQLite database.
1. Make sure you actually installed the hooks (`condense init -g`) and restarted your AI agent.
2. Check if the database path is writable. On Linux/macOS, this is `~/.local/share/condense/condense.db`. On Windows, it is `%LOCALAPPDATA%\condense\condense.db`.

### "The hook doesn't seem to be firing"
1. Verify the hook file exists (see paths above).
2. For JSON-based hooks (like Claude Code or Cline), you can test them manually by piping a mock JSON payload into the script, as shown in the Verification sections.
3. Check the AI tool's internal logs if available. For Cursor, check the Output panel (Tasks).

### "condense: command not found"
The hook fired, but your AI tool's shell environment does not have `condense` on the `PATH`.
- **Fix (macOS/Linux)**: Ensure the installation directory (usually `~/.local/bin/`) is exported in your `~/.bashrc` or `~/.zshrc`. Some AI tools run non-interactive shells that do not load full profiles. You may need to specify the absolute path in your hook scripts or ensure your `PATH` is set in the correct profile (e.g., `~/.bash_profile`).
- **Fix (Windows)**: Ensure the folder containing `condense.exe` is in your User or System `PATH` environment variable. Restart the terminal and AI tool.

### "Permission denied on macOS" (Gatekeeper)
If you downloaded the binary manually (not via the install script) or on older macOS versions, Gatekeeper might block execution.
- **Fix**: Run `xattr -d com.apple.quarantine /path/to/condense` to clear the quarantine attribute.

### "cannot be loaded because running scripts is disabled on this system" (Windows)
If PowerShell refuses to run the condense-hook.ps1 scripts, your Execution Policy is too restrictive.
- **Fix**: Open PowerShell as Administrator and run:
  ```powershell
  Set-ExecutionPolicy RemoteSigned -Scope CurrentUser
  ```
  This allows locally created scripts (like the hooks generated by `condense init`) to run, while still protecting against unsigned scripts downloaded from the internet.

### "JSON parsing error in hook script"
If you modified the hook script manually and introduced a syntax error, the fail-open mechanisms will typically allow the original command to run, but interception will fail silently.
- **Fix**: Run `condense init -g` again to safely reinstall and overwrite the hook scripts with clean templates. All condense hook files contain a `HookTemplate.SENTINEL` comment that allows condense to safely identify and upgrade them without deleting your other custom hooks.
