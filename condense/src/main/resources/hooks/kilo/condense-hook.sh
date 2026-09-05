#!/usr/bin/env bash
# Installed by: condense init
# Tool: Kilo Code
# Do not edit manually — run `condense init` to reinstall or `condense init --remove` to uninstall
#
# PreToolUse matcher Bash. Deny matching commands; never rewrite+allow.

CONDENSE_COMMANDS="{{CONDENSE_COMMANDS}}"

python3 -c '
import sys, json

try:
    data = json.load(sys.stdin)
except Exception:
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
        "permissionDecision": "deny",
        "permissionDecisionReason": "Use \"condense " + command + "\" instead to get filtered, token-efficient output."
    }))
'
