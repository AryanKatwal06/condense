#!/usr/bin/env bash
# Installed by: condense init
# Tool: Codex
# Do not edit manually — run `condense init` to reinstall or `condense init --remove` to uninstall
#
# Codex PreToolUse (matcher Bash). Deny matching shell commands; never rewrite+allow.
# Trust this hook in Codex /hooks yourself — Condense does not write vendor trust hashes.

CONDENSE_COMMANDS="git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"

python3 -c '
import sys, json

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

tool_name = data.get("tool_name") or data.get("toolName") or ""
if tool_name and tool_name not in ("Bash", "bash", "shell"):
    sys.exit(0)

tool_input = data.get("tool_input") or data.get("toolInput") or {}
command = ""
if isinstance(tool_input, dict):
    command = str(tool_input.get("command", "")).strip()
if not command:
    command = str(data.get("command", "")).strip()
if not command:
    sys.exit(0)

condense_commands = "'"$CONDENSE_COMMANDS"'".split()
bare_cmd = command.split()[0].split("/")[-1]

if bare_cmd in condense_commands:
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": "Use \"condense " + command + "\" instead to get filtered, token-efficient output."
        }
    }))
'
