#!/usr/bin/env bash
# Builds a .deb package from the native binary.
# Usage: ./packaging/deb/build-deb.sh <binary-path> <version> <arch>
# Example: ./packaging/deb/build-deb.sh target/zap-runner 0.1.0 amd64

set -euo pipefail

BINARY="${1:?Usage: $0 <binary> <version> <arch>}"
VERSION="${2:?Usage: $0 <binary> <version> <arch>}"
ARCH="${3:?Usage: $0 <binary> <version> <arch>}"

PKG_DIR="$(mktemp -d)"
trap 'rm -rf "$PKG_DIR"' EXIT

DEB_ROOT="${PKG_DIR}/zap_${VERSION}_${ARCH}"

# Directory structure
mkdir -p "${DEB_ROOT}/DEBIAN"
mkdir -p "${DEB_ROOT}/usr/bin"
mkdir -p "${DEB_ROOT}/usr/share/man/man1"
mkdir -p "${DEB_ROOT}/usr/share/bash-completion/completions"
mkdir -p "${DEB_ROOT}/usr/share/zsh/vendor-completions"
mkdir -p "${DEB_ROOT}/usr/share/fish/vendor_completions.d"

# Install binary
cp "$BINARY" "${DEB_ROOT}/usr/bin/zap"
chmod 755 "${DEB_ROOT}/usr/bin/zap"

# Install man page
gzip -c packaging/man/zap.1 > "${DEB_ROOT}/usr/share/man/man1/zap.1.gz"

# Install completions
cp packaging/completions/zap.bash "${DEB_ROOT}/usr/share/bash-completion/completions/zap"
cp packaging/completions/zap.zsh  "${DEB_ROOT}/usr/share/zsh/vendor-completions/_zap"
cp packaging/completions/zap.fish "${DEB_ROOT}/usr/share/fish/vendor_completions.d/zap.fish"

# Control file
cat > "${DEB_ROOT}/DEBIAN/control" << EOF
Package: zap
Version: ${VERSION}
Architecture: ${ARCH}
Maintainer: Your Name <you@example.com>
Description: High-performance CLI proxy for AI token savings
 Zap filters shell command output to save 60-90% of AI tokens.
Homepage: https://github.com/YOUR_ORG/zap
Section: utils
Priority: optional
EOF

# Build .deb
dpkg-deb --build --root-owner-group "$DEB_ROOT" "zap_${VERSION}_${ARCH}.deb"
echo "Built: zap_${VERSION}_${ARCH}.deb"
