#!/usr/bin/env bash
# Zap — Uninstall Script
# Removes the zap binary and optionally zap data/config

set -euo pipefail

BINARY_NAME="zap"

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
  echo "  Uninstalling Zap"
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
    if command -v zap >/dev/null 2>&1; then
      zap init --remove 2>/dev/null || true
      echo "  ✓ Hooks removed"
    fi
  fi

  # Offer to remove data
  if [ -d "${HOME}/.local/share/zap" ] || [ -d "${HOME}/.config/zap" ] || \
     [ -d "${HOME}/Library/Application Support/zap" ]; then
    echo ""
    echo "  Zap data and config directories still exist:"
    [ -d "${HOME}/.config/zap" ] && echo "    ${HOME}/.config/zap"
    [ -d "${HOME}/.local/share/zap" ] && echo "    ${HOME}/.local/share/zap"
    [ -d "${HOME}/Library/Application Support/zap" ] && \
      echo "    ${HOME}/Library/Application Support/zap"
    echo ""
    echo "  To remove them:"
    echo "    rm -rf ~/.config/zap ~/.local/share/zap"
    echo "    rm -rf ~/Library/Application\ Support/zap  # macOS"
  fi

  echo ""
  echo "  Zap uninstalled."
  echo ""
}

main "$@"
