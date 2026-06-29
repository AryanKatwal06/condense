#!/usr/bin/env bash
# Installed by: condense init -g
# Tool: Claude Code
# Do not edit manually — run `condense init -g` to reinstall or `condense init --remove` to uninstall
#
# This hook intercepts shell commands before Claude Code executes them.
# Commands matching CONDENSE_COMMANDS are routed through `condense` for output compression.

CONDENSE_COMMANDS="git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"

# Extract the bare command name from the first argument
cmd_name="${1%% *}"
bare_cmd="$(basename "$cmd_name")"

# Check if command is in the condense-handled list
if echo " $CONDENSE_COMMANDS " | grep -qw " $bare_cmd "; then
  exec condense "$@"
fi

exec "$@"
