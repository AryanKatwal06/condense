#!/usr/bin/env bash
# Condense — Install Script
# Usage: curl -fsSL https://github.com/AryanKatwal06/condense/releases/latest/download/install.sh | bash
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


REPO="AryanKatwal06/condense"

if [ -z "${CONDENSE_VERSION:-}" ] || [ "${CONDENSE_VERSION}" = "\${project.version}" ]; then
  if command -v curl >/dev/null 2>&1; then
    LATEST_TAG=$(curl -s https://api.github.com/repos/${REPO}/releases/latest | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/' || true)
  elif command -v wget >/dev/null 2>&1; then
    LATEST_TAG=$(wget -qO- https://api.github.com/repos/${REPO}/releases/latest | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/' || true)
  fi
  VERSION="${LATEST_TAG#v}"
  if [ -z "$VERSION" ]; then
    VERSION="1.0.1"
  fi
else
  VERSION="${CONDENSE_VERSION}"
fi

BASE_URL="https://github.com/${REPO}/releases/download/v${VERSION}"
BINARY_NAME="condense"


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
        x86_64)
          echo "Error: Intel macOS (x86_64) pre-built binaries are not available." >&2
          echo "Please build from source: https://github.com/${REPO}#building-from-source" >&2
          exit 1
          ;;
        arm64)   echo "macos-aarch64" ;;
        *)
          echo "Unsupported macOS architecture: $arch" >&2
          exit 1
          ;;
      esac
      ;;
    *)
      case "$os" in
        *MINGW*|*MSYS*|*CYGWIN*)
          echo "It looks like you are on Windows." >&2
          echo "Please use the PowerShell installer instead: irm https://github.com/${REPO}/releases/latest/download/install.ps1 | iex" >&2
          exit 1
          ;;
      esac
      echo "Unsupported operating system: $os" >&2
      echo "Condense supports Linux and macOS. For Windows, use install.ps1." >&2
      exit 1
      ;;
  esac
}


install_dir() {
  local os
  os="$(uname -s)"
  case "$os" in
    Linux)  echo "${HOME}/.local/bin" ;;
    Darwin) echo "/usr/local/bin" ;;
    *)      echo "${HOME}/.local/bin" ;;
  esac
}


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


main() {
  if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
    echo "Usage: install.sh [OPTIONS]"
    echo ""
    echo "Installs Condense, a high-performance CLI proxy for AI coding agents."
    echo ""
    echo "Options:"
    echo "  -h, --help    Show this help message"
    echo ""
    echo "Environment Variables:"
    echo "  CONDENSE_VERSION   Force install of a specific version (e.g. 1.0.1)"
    echo "                     If unset, defaults to the latest GitHub release."
    exit 0
  fi

  echo ""
  echo "  Installing Condense v${VERSION}"
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

  local binary_filename="condense-${platform}"
  local binary_url="${BASE_URL}/${binary_filename}"
  local checksums_url="${BASE_URL}/checksums.txt"
  local checksums_file="${tmpdir}/checksums.txt"

  # Download binary
  echo "  Downloading ${binary_filename}..."
  download "$binary_url" "${tmpdir}/${binary_filename}"

  echo "  Downloading checksums.txt..."
  download "$checksums_url" "$checksums_file"

  echo "  Verifying checksum..."
  # Extract the line matching our binary from checksums.txt
  expected_line=$(grep "$binary_filename" "$checksums_file" || true)
  if [ -z "$expected_line" ]; then
      echo "  Error: could not find checksum for $binary_filename in checksums.txt" >&2
      exit 1
  fi
  expected_hash=$(echo "$expected_line" | awk '{print $1}')

  if command -v sha256sum >/dev/null 2>&1; then
      actual_hash=$(sha256sum "${tmpdir}/${binary_filename}" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
      actual_hash=$(shasum -a 256 "${tmpdir}/${binary_filename}" | awk '{print $1}')
  else
      echo "  Warning: no sha256sum or shasum found — skipping checksum verification." >&2
      actual_hash="$expected_hash"  # skip
  fi

  if [ "$expected_hash" != "$actual_hash" ]; then
      echo "  Error: checksum verification FAILED." >&2
      echo "  Expected: $expected_hash" >&2
      echo "  Got:      $actual_hash" >&2
      exit 1
  fi
  echo "  checksum OK"

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
        echo "  Note: Add ${dir} to your PATH to use condense from anywhere:"
        echo "  For bash:  echo 'export PATH=\"\${HOME}/.local/bin:\${PATH}\"' >> ~/.bashrc && source ~/.bashrc"
        echo "  For zsh:   echo 'export PATH=\"\${HOME}/.local/bin:\${PATH}\"' >> ~/.zshrc && source ~/.zshrc"
        ;;
    esac
  fi

  # Confirm installation
  echo ""
  if "${install_path}" --version 2>/dev/null; then
    echo ""
    echo "  Condense installed successfully!"
    echo "  Run 'condense --help' to get started."
    echo "  Run 'condense init -g' to install AI tool hooks."
  else
    echo "  Warning: binary installed but 'condense --version' failed." >&2
    echo "  This may be a macOS Gatekeeper issue on first run — try:" >&2
    echo "  xattr -d com.apple.quarantine ${install_path}" >&2
  fi
  echo ""
}

main "$@"
