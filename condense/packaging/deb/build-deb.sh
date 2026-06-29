#!/usr/bin/env bash
# Builds a .deb package from the native binary.
# Usage: ./packaging/deb/build-deb.sh <binary-path> <version> <arch>
# Example: ./packaging/deb/build-deb.sh target/condense-runner 0.1.0 amd64

set -euo pipefail

BINARY="${1:?Usage: $0 <binary> <version> <arch>}"
VERSION="${2:?Usage: $0 <binary> <version> <arch>}"
ARCH="${3:?Usage: $0 <binary> <version> <arch>}"

PKG_DIR="$(mktemp -d)"
trap 'rm -rf "$PKG_DIR"' EXIT

DEB_ROOT="${PKG_DIR}/condense_${VERSION}_${ARCH}"

# Directory structure
mkdir -p "${DEB_ROOT}/DEBIAN"
mkdir -p "${DEB_ROOT}/usr/bin"
mkdir -p "${DEB_ROOT}/usr/share/man/man1"
mkdir -p "${DEB_ROOT}/usr/share/bash-completion/completions"
mkdir -p "${DEB_ROOT}/usr/share/zsh/vendor-completions"
mkdir -p "${DEB_ROOT}/usr/share/fish/vendor_completions.d"

# Install binary
cp "$BINARY" "${DEB_ROOT}/usr/bin/condense"
chmod 755 "${DEB_ROOT}/usr/bin/condense"

# Install man page
gzip -c packaging/man/condense.1 > "${DEB_ROOT}/usr/share/man/man1/condense.1.gz"

# Install completions
cp packaging/completions/condense.bash "${DEB_ROOT}/usr/share/bash-completion/completions/condense"
cp packaging/completions/condense.zsh  "${DEB_ROOT}/usr/share/zsh/vendor-completions/_condense"
cp packaging/completions/condense.fish "${DEB_ROOT}/usr/share/fish/vendor_completions.d/condense.fish"

# Control file
cat > "${DEB_ROOT}/DEBIAN/control" << EOF
Package: condense
Version: ${VERSION}
Architecture: ${ARCH}
Maintainer: Your Name <you@example.com>
Description: High-performance CLI proxy for AI token savings
 Condense filters shell command output to save 60-90% of AI tokens.
Homepage: https://github.com/YOUR_ORG/condense
Section: utils
Priority: optional
EOF

# Build .deb
dpkg-deb --build --root-owner-group "$DEB_ROOT" "condense_${VERSION}_${ARCH}.deb"
echo "Built: condense_${VERSION}_${ARCH}.deb"
