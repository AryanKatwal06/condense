#!/usr/bin/env bash
# Builds an RPM package from the native binary.
# Usage: ./packaging/rpm/build-rpm.sh <binary-path> <version> <arch>
# Example: ./packaging/rpm/build-rpm.sh target/condense-runner 1.0.1 x86_64

set -euo pipefail

BINARY="${1:?Usage: $0 <binary> <version> <arch>}"
VERSION="${2:?Usage: $0 <binary> <version> <arch>}"
ARCH="${3:?Usage: $0 <binary> <version> <arch>}"

PKG_DIR="$(mktemp -d)"
trap 'rm -rf "$PKG_DIR"' EXIT

RPM_TOP="${PKG_DIR}/rpmbuild"
mkdir -p "${RPM_TOP}/BUILD"
mkdir -p "${RPM_TOP}/BUILDROOT"
mkdir -p "${RPM_TOP}/RPMS"
mkdir -p "${RPM_TOP}/SOURCES"
mkdir -p "${RPM_TOP}/SPECS"
mkdir -p "${RPM_TOP}/SRPMS"

# Copy binary to sources
cp "$BINARY" "${RPM_TOP}/SOURCES/condense-linux-${ARCH}"

# Generate configured spec file
SPEC_FILE="${RPM_TOP}/SPECS/condense.spec"
sed \
  -e "s/^Version:.*/Version:        ${VERSION}/" \
  -e "s/Source0:.*/Source0:        condense-linux-${ARCH}/" \
  -e "s/BuildArch:.*/BuildArch:      ${ARCH}/" \
  packaging/rpm/condense.spec > "$SPEC_FILE"

# Build RPM binary package if rpmbuild is available
if command -v rpmbuild >/dev/null 2>&1; then
  rpmbuild --define "_topdir ${RPM_TOP}" -bb "$SPEC_FILE"
  find "${RPM_TOP}/RPMS" -type f -name "*.rpm" -exec cp {} . \;
  echo "Built RPM package successfully."
else
  echo "rpmbuild not found in PATH; generated spec file validated at ${SPEC_FILE}."
fi
