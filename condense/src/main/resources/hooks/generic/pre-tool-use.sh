#!/usr/bin/env bash
# Installed by: condense init -g
# Tool: Generic Bash
# Do not edit manually — run `condense init -g` to reinstall or `condense init --remove` to uninstall
#
# Execution-Wrapper Policy (GENERIC_BASH):
# Unlike agent-specific hooks which use a deny-and-suggest policy to allow agent tool orchestrators
# to re-issue commands with `condense <cmd>`, generic bash execution wrapper intercepts commands
# directly at the shell invocation boundary. It wraps matched commands via `exec condense "$@"`
# for transparent output filtering, or falls back to standard execution via `exec "$@"`.

CONDENSE_COMMANDS="{{CONDENSE_COMMANDS}}"

if [ $# -eq 0 ]; then
  exit 0
fi

raw_cmd="$1"

# Strip surrounding double or single quotes
clean_cmd="${raw_cmd#\"}"
clean_cmd="${clean_cmd%\"}"
clean_cmd="${clean_cmd#\'}"
clean_cmd="${clean_cmd%\'}"

# Normalize backslashes to forward slashes and extract basename
clean_cmd="${clean_cmd//\\//}"
bare_cmd="$(basename "$clean_cmd")"

# Strip Windows executable extensions if present
bare_cmd="${bare_cmd%.exe}"
bare_cmd="${bare_cmd%.cmd}"
bare_cmd="${bare_cmd%.bat}"

if echo " $CONDENSE_COMMANDS " | grep -qw "$bare_cmd"; then
  exec condense "$@"
fi

exec "$@"
