#!/usr/bin/env bash
# Installed by: condense init
# Tool: GitHub Copilot CLI
# Do not edit manually — run `condense init -g` to reinstall
#
# This hook intercepts shell commands before GitHub Copilot CLI executes them.
# Commands matching CONDENSE_COMMANDS are routed through `condense` for output compression.

CONDENSE_COMMANDS="git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"

python3 -c '
import sys, json

try:
    data = json.load(sys.stdin)
except Exception:
    print(json.dumps({"permissionDecision": "allow"}))
    sys.exit(0)

tool_name = data.get("toolName")
if tool_name != "bash":
    print(json.dumps({"permissionDecision": "allow"}))
    sys.exit(0)

tool_args_str = data.get("toolArgs")
if not tool_args_str:
    print(json.dumps({"permissionDecision": "allow"}))
    sys.exit(0)

try:
    tool_args = json.loads(tool_args_str)
except Exception:
    print(json.dumps({"permissionDecision": "allow"}))
    sys.exit(0)

command = tool_args.get("command", "").strip()

if not command:
    print(json.dumps({"permissionDecision": "allow"}))
    sys.exit(0)

condense_commands = "'"$CONDENSE_COMMANDS"'".split()

parts = command.split()
bare_cmd = parts[0].split("/")[-1]

if bare_cmd in condense_commands:
    print(json.dumps({
        "permissionDecision": "deny",
        "permissionDecisionReason": "Use \"condense <command>\" instead to get filtered, token-efficient output."
    }))
else:
    print(json.dumps({"permissionDecision": "allow"}))
'
