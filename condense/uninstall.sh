#!/usr/bin/env bash
# Condense — Uninstall Script
# Removes the condense binary and optionally condense data/config

set -euo pipefail

BINARY_NAME="condense"

uninstall_dir() {
  local os
  os="$(uname -s)"
  case "$os" in
    Linux)  echo "${HOME}/.local/bin" ;;
    Darwin) echo "/usr/local/bin" ;;
    *)      echo "${HOME}/.local/bin" ;;
  esac
}

main() {
  local dir
  dir="$(uninstall_dir)"
  local binary="${dir}/${BINARY_NAME}"

  echo ""
  echo "  Uninstalling Condense"
  echo ""

  # Remove binary
  if [ -f "$binary" ]; then
    rm -f "$binary"
    echo "  ✓ Removed ${binary}"
  else
    echo "  • Binary not found at ${binary} (already removed?)"
  fi

  # Remove hooks if requested
  if [ "${1:-}" = "--remove-hooks" ]; then
    echo "  Removing AI tool hooks..."
    if command -v condense >/dev/null 2>&1; then
      condense init --remove 2>/dev/null || true
      echo "  ✓ Hooks removed"
    fi
  fi

  # Offer to remove data
  local os
  os="$(uname -s)"
  local config_dir data_dir
  case "$os" in
    Darwin)
      config_dir="${HOME}/Library/Application Support/condense"
      data_dir="${HOME}/Library/Application Support/condense"
      ;;
    *)
      # Linux / Unix — check XDG environment variables first (matching PlatformDirs.java)
      if [ -n "${XDG_CONFIG_HOME:-}" ] && [ -n "$(echo "${XDG_CONFIG_HOME}" | tr -d '[:space:]')" ]; then
        config_dir="${XDG_CONFIG_HOME}/condense"
      else
        config_dir="${HOME}/.config/condense"
      fi
      if [ -n "${XDG_DATA_HOME:-}" ] && [ -n "$(echo "${XDG_DATA_HOME}" | tr -d '[:space:]')" ]; then
        data_dir="${XDG_DATA_HOME}/condense"
      else
        data_dir="${HOME}/.local/share/condense"
      fi
      ;;
  esac

  local existing_dirs=()
  [ -d "$config_dir" ] && existing_dirs+=("$config_dir")
  if [ "$config_dir" != "$data_dir" ] && [ -d "$data_dir" ]; then
    existing_dirs+=("$data_dir")
  fi

  if [ ${#existing_dirs[@]} -gt 0 ]; then
    echo ""
    echo "  Condense data and config directories still exist:"
    for ed in "${existing_dirs[@]}"; do
      echo "    $ed"
    done
    echo ""
    echo "  To remove them:"
    local rm_cmd="    rm -rf"
    for ed in "${existing_dirs[@]}"; do
      rm_cmd+=" \"$ed\""
    done
    echo "$rm_cmd"
  fi

  echo ""
  echo "  Condense uninstalled."
  echo ""
}

main "$@"
