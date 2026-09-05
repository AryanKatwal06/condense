#!/usr/bin/env bash
# Installed by: condense init
# Tool: Claude Code (Script Hook)
# Do not edit manually — run `condense init` to reinstall or `condense init --remove` to uninstall
#
# This script reads the JSON-over-stdin PreToolUse protocol from Claude Code.
# Matched bare commands are denied with a retry message. They are never rewritten
# and auto-allowed.

CONDENSE_COMMANDS="{{CONDENSE_COMMANDS}}"

python3 -c '
import sys, json

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)


if data.get("tool_name") != "Bash":
    sys.exit(0)

tool_input = data.get("tool_input", {})
command = tool_input.get("command", "").strip()

if not command:
    sys.exit(0)

condense_commands = "'"$CONDENSE_COMMANDS"'".split()

parts = command.split()
bare_cmd = parts[0].split("/")[-1]

if bare_cmd in condense_commands:
    response = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": "Use \"condense " + command + "\" instead to get filtered, token-efficient output."
        }
    }
    print(json.dumps(response))
'
