#!/usr/bin/env bash
# Installed by: condense init
# Tool: Claude Code (Script Hook)
# Do not edit manually — run `condense init` to reinstall or `condense init --remove` to uninstall
#
# This script reads the JSON-over-stdin PreToolUse protocol from Claude Code.

CONDENSE_COMMANDS="git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"

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

condense_commands = "'"$CONDENSE_COMMANDS"'".split()

parts = command.split()
bare_cmd = parts[0].split("/")[-1]

if bare_cmd in condense_commands:
    updated_input = tool_input.copy()
    updated_input["command"] = "condense " + command
    
    response = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "allow",
            "updatedInput": updated_input
        }
    }
    print(json.dumps(response))
'
