#!/usr/bin/env bash
# Installed by: zap init -g
# Tool: Claude Code
# Do not edit manually — run `zap init -g` to reinstall or `zap init --remove` to uninstall
#
# This hook intercepts shell commands before Claude Code executes them.
# Commands matching ZAP_COMMANDS are routed through `zap` for output compression.

ZAP_COMMANDS="git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"

# Extract the bare command name from the first argument
cmd_name="${1%% *}"
bare_cmd="$(basename "$cmd_name")"

# Check if command is in the zap-handled list
if echo " $ZAP_COMMANDS " | grep -qw " $bare_cmd "; then
  exec zap "$@"
fi

exec "$@"
