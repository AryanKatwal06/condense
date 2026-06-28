#!/usr/bin/env bash
# Zap — Install Script
# Usage: curl -fsSL https://raw.githubusercontent.com/YOUR_ORG/zap/main/install.sh | sh
#
# This script:
#   1. Detects your OS and architecture
#   2. Downloads the correct native binary from GitHub Releases
#   3. Verifies the SHA-256 checksum
#   4. Installs to ~/.local/bin (Linux) or /usr/local/bin (macOS)
#   5. Prints the installed version to confirm success
#
# Supported platforms:
#   Linux x64 (glibc and musl — fully static)
#   Linux aarch64 (fully static)
#   macOS x64 (Intel)
#   macOS aarch64 (Apple Silicon)
#
# Requirements: curl or wget, sha256sum or shasum

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────────────────────

REPO="YOUR_ORG/zap"
VERSION="${ZAP_VERSION:-${project.version}}"
BASE_URL="https://github.com/${REPO}/releases/download/v${VERSION}"
BINARY_NAME="zap"

# ── Platform detection ────────────────────────────────────────────────────────

detect_platform() {
  local os arch

  os="$(uname -s)"
  arch="$(uname -m)"

  case "$os" in
    Linux)
      case "$arch" in
        x86_64)  echo "linux-x64" ;;
        aarch64) echo "linux-aarch64" ;;
        arm64)   echo "linux-aarch64" ;;
        *)
          echo "Unsupported Linux architecture: $arch" >&2
          echo "Please build from source: https://github.com/${REPO}#build-from-source" >&2
          exit 1
          ;;
      esac
      ;;
    Darwin)
      case "$arch" in
        x86_64)  echo "macos-x64" ;;
        arm64)   echo "macos-aarch64" ;;
        *)
          echo "Unsupported macOS architecture: $arch" >&2
          exit 1
          ;;
      esac
      ;;
    *)
      echo "Unsupported operating system: $os" >&2
      echo "Zap supports Linux and macOS. Windows support is planned." >&2
      exit 1
      ;;
  esac
}

# ── Install directory ─────────────────────────────────────────────────────────

install_dir() {
  local os
  os="$(uname -s)"
  case "$os" in
    Linux)  echo "${HOME}/.local/bin" ;;
    Darwin) echo "/usr/local/bin" ;;
    *)      echo "${HOME}/.local/bin" ;;
  esac
}

# ── Download helper ───────────────────────────────────────────────────────────

download() {
  local url="$1"
  local dest="$2"

  if command -v curl >/dev/null 2>&1; then
    curl --progress-bar --location --fail --output "$dest" "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget --quiet --show-progress --output-document="$dest" "$url"
  else
    echo "Error: neither curl nor wget found. Install one and try again." >&2
    exit 1
  fi
}

# ── Checksum verification ─────────────────────────────────────────────────────

verify_checksum() {
  local binary="$1"
  local checksum_file="$2"

  if command -v sha256sum >/dev/null 2>&1; then
    # Linux: sha256sum expects "hash  filename" format
    local expected
    expected="$(cat "$checksum_file")"
    echo "${expected}  ${binary}" | sha256sum --check --status
  elif command -v shasum >/dev/null 2>&1; then
    # macOS: shasum -a 256
    local expected
    expected="$(cat "$checksum_file")"
    echo "${expected}  ${binary}" | shasum -a 256 --check --status
  else
    echo "Warning: no sha256sum or shasum found — skipping checksum verification." >&2
    echo "Install coreutils (Linux) or use built-in shasum (macOS) for security." >&2
    return 0
  fi
}

# ── Main ──────────────────────────────────────────────────────────────────────

main() {
  echo ""
  echo "  Installing Zap v${VERSION}"
  echo "  Repository: https://github.com/${REPO}"
  echo ""

  # Detect platform
  local platform
  platform="$(detect_platform)"
  echo "  Platform:   ${platform}"

  # Determine install directory
  local dir
  dir="$(install_dir)"
  echo "  Install to: ${dir}/${BINARY_NAME}"
  echo ""

  # Create install directory if needed
  mkdir -p "$dir"

  # Temporary directory for download
  local tmpdir
  tmpdir="$(mktemp -d)"
  trap 'rm -rf "$tmpdir"' EXIT

  local binary_filename="zap-${platform}"
  local checksum_filename="zap-${platform}.sha256"
  local binary_url="${BASE_URL}/${binary_filename}"
  local checksum_url="${BASE_URL}/${checksum_filename}"

  # Download binary
  echo "  Downloading ${binary_filename}..."
  download "$binary_url" "${tmpdir}/${binary_filename}"

  # Download checksum
  echo "  Downloading ${checksum_filename}..."
  download "$checksum_url" "${tmpdir}/${checksum_filename}"

  # Verify checksum
  echo "  Verifying SHA-256 checksum..."
  if ! verify_checksum "${tmpdir}/${binary_filename}" "${tmpdir}/${checksum_filename}"; then
    echo ""
    echo "  Error: checksum verification FAILED." >&2
    echo "  The downloaded binary may be corrupted or tampered with." >&2
    echo "  Please try again or download manually from:" >&2
    echo "  ${binary_url}" >&2
    exit 1
  fi
  echo "  ✓ Checksum OK"

  # Install binary
  local install_path="${dir}/${BINARY_NAME}"
  cp "${tmpdir}/${binary_filename}" "$install_path"
  chmod 755 "$install_path"

  echo "  ✓ Installed to ${install_path}"

  # Add to PATH hint (Linux only — macOS /usr/local/bin is already on PATH)
  local os
  os="$(uname -s)"
  if [ "$os" = "Linux" ]; then
    case ":${PATH}:" in
      *":${dir}:"*) ;;  # already on PATH
      *)
        echo ""
        echo "  Note: Add ${dir} to your PATH to use zap from anywhere:"
        echo "  For bash:  echo 'export PATH=\"\${HOME}/.local/bin:\${PATH}\"' >> ~/.bashrc && source ~/.bashrc"
        echo "  For zsh:   echo 'export PATH=\"\${HOME}/.local/bin:\${PATH}\"' >> ~/.zshrc && source ~/.zshrc"
        ;;
    esac
  fi

  # Confirm installation
  echo ""
  if "${install_path}" --version 2>/dev/null; then
    echo ""
    echo "  Zap installed successfully!"
    echo "  Run 'zap --help' to get started."
    echo "  Run 'zap init -g' to install AI tool hooks."
  else
    echo "  Warning: binary installed but 'zap --version' failed." >&2
    echo "  This may be a macOS Gatekeeper issue on first run — try:" >&2
    echo "  xattr -d com.apple.quarantine ${install_path}" >&2
  fi
  echo ""
}

main "$@"
