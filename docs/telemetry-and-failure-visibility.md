# Telemetry and Failure Visibility Specification

## Overview

Condense provides opt-in failure visibility and crash diagnostics to help maintainers improve filter quality, discover common agent command failures, and troubleshoot ecosystem breakages.

To guarantee user privacy and maintain security integrity, Condense enforces a **strict zero-knowledge privacy threat model**.

---

## Privacy Threat Model

### 1. Zero Command and Content Collection Guarantee
Condense never collects, inspects, or transmits:
- **Zero raw command lines**: Commands executed by agents or users are never transmitted.
- **Zero output snippets or error streams**: Standard output, standard error, and compiler logs are never transmitted.
- **Zero file paths**: Workspace paths, filenames, and directory hierarchies are never transmitted.
- **Zero environment variables or credentials**: No API keys, tokens, usernames, or secrets can ever leak.
- **Zero repository metadata**: Git remotes, branches, commit hashes, and project names are excluded.

### 2. Bucketed Metadata to Prevent Statistical Re-identification
Continuous variables are quantized into discrete buckets to prevent side-channel timing or sizing fingerprinting:
- **Duration Bucket**: `<100ms`, `100ms-1s`, `1s-5s`, `>5s`
- **Output Length Bucket**: `<1KB`, `1KB-10KB`, `10KB-100KB`, `>100KB`

---

## Wire Schema (`v1`)

Sanitized failure reports adhere to the following JSON schema:

```json
{
  "schema_version": 1,
  "report_id": "c1f7a29e-8b43-416b-9cf9-7e4a7a8d5e12",
  "timestamp": "2026-03-01T12:00:00Z",
  "condense_version": "1.0.1",
  "os_family": "Linux",
  "os_arch": "amd64",
  "failure_stage": "PROXY_EXECUTION",
  "error_category": "COMPILATION_ERROR",
  "exit_code": 1,
  "duration_bucket": "1s-5s",
  "output_length_bucket": "10KB-100KB"
}
```

---

## Consent Lifecycle

1. **Default State**: Strictly **OPT-OUT (Disabled)**. Out of the box, Condense never opens a network socket or initiates any HTTP request for telemetry.
2. **Opting In**: Users must explicitly run:
   ```bash
   condense report --opt-in
   ```
3. **Opting Out**: Users can revoke consent at any time:
   ```bash
   condense report --opt-out
   ```
4. **Purging Local State**: Users can delete all stored consent and telemetry records:
   ```bash
   condense report --purge
   ```
5. **Auditing and Preview**: Users can inspect the exact payload before sending:
   ```bash
   condense report --preview
   condense report --export ./failure-report.json
   ```

---

## Rate Limits and Network Safety

- **Daily Quota**: Maximum of 10 reports per calendar day per client. Additional reports are suppressed locally without network contact.
- **Endpoint Pinning**: Reports are transmitted exclusively to the pinned HTTPS endpoint `https://telemetry.condense.dev/v1/failures` unless explicitly overridden.
- **Fail-Open Resilience**: Any network timeout, DNS resolution failure, or HTTP error fails silently. Failure reporting never blocks command execution, alters exit codes, or impacts CLI startup latency.

---

## Compile-Time Kill Switch

Organizations or environments requiring complete elimination of telemetry can disable it entirely via system property:

```bash
-Dcondense.telemetry.disabled=true
```

When active, all telemetry methods immediately return without reading configuration or touching the network.
