import sys
with open('condense/install.sh', 'r', newline='') as f:
    lines = f.readlines()
new_lines = []
for line in lines:
    if line.startswith('# ──'):
        continue
    new_lines.append(line)
content = ''.join(new_lines)

old_block = '''  local binary_filename="condense-${platform}"
  local checksum_filename="condense-${platform}.sha256"
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
  echo "  ✓ Checksum OK"'''

new_block = '''  local binary_filename="condense-${platform}"
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
  echo "  checksum OK"'''

if old_block in content:
    content = content.replace(old_block, new_block)
else:
    print('Failed to find old block in install.sh')

verify_checksum_func = '''verify_checksum() {
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

'''
content = content.replace(verify_checksum_func, '')

with open('condense/install.sh', 'w', newline='') as f:
    f.write(content)
print("Done!")
