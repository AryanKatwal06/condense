#!/usr/bin/env bash
# Installed by: condense init
# Tool: Cursor
# Do not edit manually — run `condense init` to reinstall or `condense init --remove` to uninstall
#
# This script reads the JSON-over-stdin beforeShellExecution protocol from Cursor.

CONDENSE_COMMANDS="git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"

python3 -c '
import sys, json

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

# Extract top-level command
command = data.get("command", "").strip()

if not command:
    sys.exit(0)

condense_commands = "'"$CONDENSE_COMMANDS"'".split()

parts = command.split()
bare_cmd = parts[0].split("/")[-1]

if bare_cmd in condense_commands:
    # Deny and redirect
    response = {
        "continue": False,
        "permission": "deny",
        "userMessage": "Routing through condense for compressed output.",
        "agentMessage": f"Use \"condense {command}\" instead to get filtered, token-efficient output."
    }
    print(json.dumps(response))
else:
    # Allow unchanged
    response = {
        "continue": True,
        "permission": "allow"
    }
    print(json.dumps(response))
'
