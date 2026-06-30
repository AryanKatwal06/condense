#!/usr/bin/env bash
# Installed by: condense init
# Tool: Windsurf
# Do not edit manually — run `condense init -g` to reinstall
#
# This hook intercepts shell commands before Windsurf executes them.
# Commands matching CONDENSE_COMMANDS are routed through `condense` for output compression.

CONDENSE_COMMANDS="git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"

python3 -c '
import sys, json

def extract_shell_command(root):
    if not isinstance(root, dict):
        return None
    cmd = root.get("tool_info", {}).get("command")
    if cmd: return cmd
    cmd = root.get("command")
    if cmd: return cmd
    cmd = root.get("tool_input", {}).get("command")
    if cmd: return cmd
    return None

try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)

command = extract_shell_command(data)

if not command or not isinstance(command, str):
    sys.exit(0)

command = command.strip()
if not command:
    sys.exit(0)

condense_commands = "'"$CONDENSE_COMMANDS"'".split()

parts = command.split()
bare_cmd = parts[0].split("/")[-1]

if bare_cmd in condense_commands:
    print("Use \"condense <command>\" instead to get filtered, token-efficient output.")
    sys.exit(2)
else:
    sys.exit(0)
'
