# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.0.x   | ✅ Yes     |

## Reporting a Vulnerability

Please do **not** report security vulnerabilities through public GitHub Issues.

Instead, report them via GitHub's private security advisory feature:
1. Go to the Security tab of this repository
2. Click "Report a vulnerability"
3. Provide a detailed description of the issue

You should receive a response within 72 hours. If the vulnerability is confirmed,
we will release a patch as soon as possible (targeting within 7 days for critical
issues).

## Security Model

Condense is a local CLI tool. Its security surface is:

- **Local SQLite database** (`~/.local/share/condense/condense.db`): stores command names
  and token counts only. No credentials, no file content, no secrets.
- **Shell hooks**: installed into AI tool hook directories by `condense init -g`.
  Hook files are plain shell scripts — review them before running `condense init -g`.
- **No network calls** during normal operation. Hook scripts are generated from templates bundled inside the binary — no network access is required for `condense init`.
- **Process execution**: Condense executes the real shell command as a child process.
  It does not modify command arguments.

## Binary Verification

GitHub Releases ship `checksums.txt` (SHA-256 of every published blob) plus
keyless Sigstore signatures. There are no per-file `.sha256` sidecars.

Download the binary, `checksums.txt`, and the matching `.sig` / `.cert` files
from the release, then:

```bash
# Checksum (Linux amd64 example)
curl -LO https://github.com/AryanKatwal06/condense/releases/download/v1.0.1/condense-linux-x64
curl -LO https://github.com/AryanKatwal06/condense/releases/download/v1.0.1/checksums.txt
sha256sum --check --ignore-missing checksums.txt

# Cosign (keyless, GitHub Actions OIDC)
cosign verify-blob \
  --certificate condense-linux-x64.cert \
  --signature condense-linux-x64.sig \
  --certificate-identity-regexp "https://github.com/AryanKatwal06/condense/.*" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  condense-linux-x64
```

The same `checksums.txt` also covers `condense-linux-aarch64`,
`condense-macos-aarch64`, `condense-windows-x64.exe`, the `.deb`, and
`sbom.cyclonedx.json`. Each of those files is signed. The CycloneDX SBOM is
produced from the source tree; Graal native images are not bit-identical
across rebuilds, so reproducibility is the runtime-dependency allowlist in
`mvn test`, the signed SBOM, and the signed checksums — not a second native
rebuild matching SHA-256.

