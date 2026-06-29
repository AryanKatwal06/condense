#!/usr/bin/env bash
# Installed by: condense init -g
# Tool: Windsurf
# Do not edit manually — run `condense init -g` to reinstall

CONDENSE_COMMANDS="git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"

cmd_name="${1%% *}"
bare_cmd="$(basename "$cmd_name")"

if echo " $CONDENSE_COMMANDS " | grep -qw " $bare_cmd "; then
  exec condense "$@"
fi

exec "$@"
