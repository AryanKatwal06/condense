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
  if [ -d "${HOME}/.local/share/condense" ] || [ -d "${HOME}/.config/condense" ] || \
     [ -d "${HOME}/Library/Application Support/condense" ]; then
    echo ""
    echo "  Condense data and config directories still exist:"
    [ -d "${HOME}/.config/condense" ] && echo "    ${HOME}/.config/condense"
    [ -d "${HOME}/.local/share/condense" ] && echo "    ${HOME}/.local/share/condense"
    [ -d "${HOME}/Library/Application Support/condense" ] && \
      echo "    ${HOME}/Library/Application Support/condense"
    echo ""
    echo "  To remove them:"
    echo "    rm -rf ~/.config/condense ~/.local/share/condense"
    echo "    rm -rf ~/Library/Application\ Support/condense  # macOS"
  fi

  echo ""
  echo "  Condense uninstalled."
  echo ""
}

main "$@"
