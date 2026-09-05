#!/usr/bin/env bash
# Installed by: condense init -g
# Tool: Claude Code
# Do not edit manually — run `condense init -g` to reinstall or `condense init --remove` to uninstall
#
# This hook intercepts shell commands before Claude Code executes them.
# Commands matching CONDENSE_COMMANDS are routed through `condense` for output compression.

CONDENSE_COMMANDS="{{CONDENSE_COMMANDS}}"

cmd_name="${1%% *}"
bare_cmd="$(basename "$cmd_name")"

if echo " $CONDENSE_COMMANDS " | grep -qw " $bare_cmd "; then
  exec condense "$@"
fi

exec "$@"
