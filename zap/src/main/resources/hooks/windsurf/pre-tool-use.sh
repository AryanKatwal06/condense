#!/usr/bin/env bash
# Installed by: zap init -g
# Tool: Windsurf
# Do not edit manually — run `zap init -g` to reinstall

ZAP_COMMANDS="git cargo pytest go test npm npx docker kubectl aws ls grep rg find cat make mvn gradle"

cmd_name="${1%% *}"
bare_cmd="$(basename "$cmd_name")"

if echo " $ZAP_COMMANDS " | grep -qw " $bare_cmd "; then
  exec zap "$@"
fi

exec "$@"
