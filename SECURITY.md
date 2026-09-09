# Security Policy

## Supported Versions

| Version | Supported | Maintenance Status |
|---------|-----------|-------------------|
| 1.0.x   | ✅ Yes     | Active Line        |

Older releases (< 1.0.0) are unsupported. Users are advised to upgrade to the latest 1.0.x release immediately.

## Reporting a Vulnerability & Security SLAs

Please do **not** report security vulnerabilities through public GitHub Issues or discussions.

Instead, report them privately via GitHub's Security Advisory feature:
1. Go to the **Security** tab of this repository (https://github.com/AryanKatwal06/condense/security/advisories/new)
2. Click **"Report a vulnerability"**
3. Provide a detailed description, reproduction steps, and affected versions.

### Response and Remediation SLAs

Condense commits to the following Service Level Agreements (detailed in [docs/backward-compatibility-sla.md](docs/backward-compatibility-sla.md)):

| Severity (CVSS) | Initial Response & Triage | Patch Release Target |
|---|---|---|
| **Critical** (9.0–10.0) | Within **24 hours** | Within **7 days** |
| **High** (7.0–8.9) | Within **48 hours** | Within **14 days** |
| **Medium** (4.0–6.9) | Within **72 hours** | Within **30 days** |
| **Low** (0.1–3.9) | Within **72 hours** | Next scheduled release |

## Threat Model & Security Boundaries

A comprehensive code-specific threat model is maintained in [docs/threat-model.md](docs/threat-model.md). Condense enforces 7 distinct security boundaries:

1. **Child Process Execution**: Argv preservation, verbatim execution, 10 MiB stream and 1 MiB line caps, PATHEXT resolution.
2. **Hook System**: Conservative finite-state compound command analyzer; fails closed on syntax ambiguity; tamper-evident hooks.
3. **MCP Server**: Stdio-only transport, path traversal prevention via `SafePathValidator`, strict JSON-RPC dispatching.
4. **Structured Parsers**: XXE and entity expansion defenses in XML/TRX parsers, bounded decompression in binlogs, capped NDJSON depth.
5. **Configuration & Trust**: Cryptographic hash gating for repository `.condense/filters.toml`, static capability ceilings.
6. **Local Persistence**: Parameterized SQL queries, WAL mode SQLite, zero storage of command arguments or output secrets.
7. **Supply Chain & Release**: Locked runtime coordinates, CycloneDX SBOM, Cosign keyless signatures, SHA-256 checksums.

## Binary Verification

GitHub Releases ship `checksums.txt` (SHA-256 of every published blob) plus keyless Sigstore cosign signatures. There are no per-file `.sha256` sidecars.

### Verification on Linux / macOS

```bash
# Download binary, checksums, signature, and certificate
curl -LO https://github.com/AryanKatwal06/condense/releases/download/v1.0.1/condense-linux-x64
curl -LO https://github.com/AryanKatwal06/condense/releases/download/v1.0.1/checksums.txt
curl -LO https://github.com/AryanKatwal06/condense/releases/download/v1.0.1/condense-linux-x64.sig
curl -LO https://github.com/AryanKatwal06/condense/releases/download/v1.0.1/condense-linux-x64.cert

# Verify SHA-256 checksum
sha256sum --check --ignore-missing checksums.txt

# Verify Cosign signature (keyless, GitHub Actions OIDC)
cosign verify-blob \
  --certificate condense-linux-x64.cert \
  --signature condense-linux-x64.sig \
  --certificate-identity-regexp "https://github.com/AryanKatwal06/condense/.*" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  condense-linux-x64
```

### Verification on Windows (PowerShell)

```powershell
# Verify SHA-256 checksum
$expected = (Get-Content checksums.txt | Select-String "condense-windows-x64.exe").Line.Split(" ")[0]
$actual = (Get-FileHash .\condense-windows-x64.exe -Algorithm SHA256).Hash.ToLower()
if ($expected -ne $actual) { throw "Checksum mismatch!" } else { Write-Host "Checksum verified OK" }

# Verify Cosign signature
cosign verify-blob `
  --certificate condense-windows-x64.exe.cert `
  --signature condense-windows-x64.exe.sig `
  --certificate-identity-regexp "https://github.com/AryanKatwal06/condense/.*" `
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" `
  condense-windows-x64.exe
```

The same `checksums.txt` covers `condense-linux-x64`, `condense-linux-aarch64`, `condense-macos-aarch64`, `condense-windows-x64.exe`, the `.deb`, and `sbom.cyclonedx.json`. Each published blob has a matching `.sig` and `.cert`. Release reproducibility is audited via `RuntimeDependencyAllowlistTest`, the signed SBOM, and signed checksums.


