# Backward Compatibility & Security SLAs

**Version:** 1.0 (September 2026)  
**Status:** Canonical Project Policy  

---

## 1. Versioning & Backward Compatibility Policy

Condense adheres strictly to [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html) (`MAJOR.MINOR.PATCH`):

1. **PATCH Releases (`x.y.Z`)**:
   - Backward-compatible bug fixes, reliability improvements, and security patches.
   - Zero changes to public CLI options, schema structures, or configuration formats.
2. **MINOR Releases (`x.Y.z`)**:
   - Backward-compatible new features: additional filter definitions, new command support, new MCP tools, and additive schema fields.
   - Additive-only schema changes (e.g. adding optional properties to structured IR envelopes or adding non-breaking columns to SQLite via automated migrations).
3. **MAJOR Releases (`X.y.z`)**:
   - Breaking changes to CLI option syntax, configuration schema, or programmatic IR types.
   - Breaking changes require a documented migration path and at least one minor release cycle with deprecation notices before removal.

---

## 2. Schema Lifecycles & Version Invariants

Condense maintains explicit versioning tags across all structured boundaries:

| Schema Surface | Current Version | Compatibility Contract | Forward / Backward Handling |
|---|---|---|---|
| **Filter Schema** | `1` | Strictly additive stage capabilities; unknown stage types fail closed during validation. | Old binary skips unknown schema tiers; new binary loads older filter TOMLs without modification. |
| **Intermediate Representation (IR)** | `1` | Root envelope properties are stable. `schema_version = 1`. New kinds (`git`, `build`, `test`, `terraform`) are additive. | Parsers ignore unknown optional fields; JSON format supports `opaque` fallback. |
| **MCP Server Protocol** | `1` | Implements Model Context Protocol 2024-11-05 standard; tools and resources are closed and verified. | Unknown tool requests return standard JSON-RPC errors; tool parameters are strictly typed. |
| **SQLite Analytics Database** | `3` | Automated migrations managed by `SchemaMigrator`. | Older databases automatically migrate forward on startup; unknown future schemas trigger read-only or fail-open bypass without proxy disruption. |

---

## 3. Deprecation Cycle Policy

1. **Deprecation Notice**: Any feature, flag, or configuration property scheduled for deprecation must emit a clear diagnostic notice to stderr (e.g. `condense: warning: --old-flag is deprecated and will be removed in v2.0.0`).
2. **Grace Period**: Deprecated features remain functional throughout the remainder of the current major version cycle.
3. **Removal Gate**: Deprecated interfaces may only be removed in a subsequent major version release.

---

## 4. Security Vulnerability Response SLAs

Condense treats security vulnerability remediation as a top-priority operational commitment:

| Vulnerability Severity | Initial Triage & Response SLA | Fix / Patch Release SLA | Public Disclosure SLA |
|---|---|---|---|
| **Critical** (CVSS 9.0–10.0) | Within **24 hours** | Within **7 days** | After patch release + 7 days coordinated window |
| **High** (CVSS 7.0–8.9) | Within **48 hours** | Within **14 days** | After patch release + 14 days coordinated window |
| **Medium** (CVSS 4.0–6.9) | Within **72 hours** | Within **30 days** | Standard release cycle |
| **Low** (CVSS 0.1–3.9) | Within **72 hours** | Next scheduled minor/patch | Standard release cycle |

### Reporting Protocol

- Security vulnerabilities must be reported privately via **GitHub Security Advisories**:
  - Navigate to https://github.com/AryanKatwal06/condense/security/advisories/new
- Do **not** open public GitHub issues or discussions for unpatched security vulnerabilities.
- Reporters receive acknowledgment and tracking updates within the triage window.

---

## 5. Supported Versions

| Release Line | Status | Maintenance End Date |
|---|---|---|
| **1.0.x** | ✅ Actively Supported | Current Active Line |
| **< 1.0.0** | ❌ Unsupported | Superseded by 1.0.x |
