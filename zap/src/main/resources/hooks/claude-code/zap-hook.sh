#!/usr/bin/env bash
# Installed by: zap init
# Tool: Claude Code (Script Hook)
# Do not edit manually — run `zap init` to reinstall or `zap init --remove` to uninstall
#
# This script reads the JSON-over-stdin PreToolUse protocol from Claude Code.

ZAP_COMMANDS="git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"

python3 -c '
import sys, json

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

# Make sure this is a Bash tool use
if data.get("tool_name") != "Bash":
    sys.exit(0)

tool_input = data.get("tool_input", {})
command = tool_input.get("command", "").strip()

if not command:
    sys.exit(0)

zap_commands = "'"$ZAP_COMMANDS"'".split()

parts = command.split()
bare_cmd = parts[0].split("/")[-1]

if bare_cmd in zap_commands:
    updated_input = tool_input.copy()
    updated_input["command"] = "zap " + command
    
    response = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "allow",
            "updatedInput": updated_input
        }
    }
    print(json.dumps(response))
'
