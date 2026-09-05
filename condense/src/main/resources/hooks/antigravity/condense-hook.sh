#!/usr/bin/env bash
# Installed by: condense init
# Tool: Antigravity
# Do not edit manually — run `condense init` to reinstall or `condense init --remove` to uninstall
#
# PreToolUse matcher run_command. Responses are deny/ask only — never rewrite+allow.

CONDENSE_COMMANDS="{{CONDENSE_COMMANDS}}"

python3 -c '
import sys, json

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

def extract_command(payload):
    if isinstance(payload.get("tool_input"), dict) and payload["tool_input"].get("command"):
        return str(payload["tool_input"]["command"])
    if isinstance(payload.get("parameters"), dict) and payload["parameters"].get("command"):
        return str(payload["parameters"]["command"])
    if payload.get("command"):
        return str(payload["command"])
    return ""

command = extract_command(data).strip()
if not command:
    sys.exit(0)

condense_commands = "'"$CONDENSE_COMMANDS"'".split()
bare_cmd = command.split()[0].split("/")[-1]

if bare_cmd in condense_commands:
    print(json.dumps({
        "decision": "deny",
        "reason": "Use \"condense " + command + "\" instead to get filtered, token-efficient output."
    }))
'
