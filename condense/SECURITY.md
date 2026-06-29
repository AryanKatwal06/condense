# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | ✅ Yes     |

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
- **No network calls** during normal operation. The only network access is
  `condense init` downloading hook templates from GitHub during first install.
- **Process execution**: Condense executes the real shell command as a child process.
  It does not modify command arguments.

## Binary Verification

Every release binary has a corresponding `.sha256` checksum file. Verify before
running:
```bash
# Download binary and checksum
curl -LO https://github.com/YOUR_ORG/condense/releases/download/v0.1.0/condense-linux-x64
curl -LO https://github.com/YOUR_ORG/condense/releases/download/v0.1.0/condense-linux-x64.sha256

# Verify
echo "$(cat condense-linux-x64.sha256)  condense-linux-x64" | sha256sum --check
```
