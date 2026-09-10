# Condense Disaster Recovery and Rollback Runbook

This document defines operational procedures, failure recovery runbooks, and database schema compatibility guarantees for **Condense**.

---

## 1. Incident Severity Definitions

- **Sev-1 (Critical)**:
  - Condense mutates a child command's exit code (e.g. child exits `1`, Condense exits `0`).
  - Terminal corruption or stdout truncation that drops critical diagnostic errors.
  - Persistent SQLite database lock preventing CLI commands from running.
- **Sev-2 (Major)**:
  - Filter regression causing excessive verbosity or unwanted token passthrough.
  - Hook integration failure preventing shell interception.
  - Update or uninstall command error.
- **Sev-3 (Minor)**:
  - Minor cosmetic or formatting irregularity in `--format json` or CLI summary.
  - Documentation drift or outdated metric claim.

---

## 2. Emergency Binary Rollback Runbook

If a Sev-1 regression is detected in a deployed release, follow this emergency procedure:

### Step 1: Immediate Channel Revocation
1. Pull the affected release tag from active distribution or mark the GitHub Release as pre-release/yanked.
2. In the update manifest (`releases.json`), point the active channel URL back to the prior known-good version (e.g. revert `1.0.2` back to `1.0.1`).

### Step 2: Client-Side Binary Reversion
Developers can revert immediately to the previous binary without losing configuration or analytics:

**Linux / macOS:**
```bash
# In-place downgrade to prior version
condense update --version 1.0.1
```

Or manually replace the executable:
```bash
cp /usr/local/bin/condense.backup /usr/local/bin/condense
```

**Windows (PowerShell):**
```powershell
# Restore previous binary
Copy-Item "$env:LOCALAPPDATA\condense\bin\condense.exe.bak" "$env:LOCALAPPDATA\condense\bin\condense.exe" -Force
```

### Step 3: Temporary Emergency Bypass (Fail-Safe)
If Condense must be bypassed instantly across an entire workstation or agent session without uninstalling:
```bash
# Export bypass flag to turn condense into a no-op passthrough proxy
export CONDENSE_PASSTHROUGH=1
```
Or temporarily remove the hook wrapper:
```bash
condense hooks --uninstall
```

---

## 3. Database Schema Compatibility and Recovery

Condense uses SQLite with Write-Ahead Logging (WAL) for analytics and audit tracking (`~/.local/share/condense/condense.db` or `%LOCALAPPDATA%\condense\condense.db`).

### Schema Evolution Contract (`SchemaMigrator`)

- **Additive Changes Only**: New columns or tables are always added as optional or nullable with default values.
- **Forward Compatibility (`schemaAhead`)**:
  - Every Condense binary checks `PRAGMA user_version` against its internal `TARGET_VERSION`.
  - If a downgraded binary encounters a database created or migrated by a newer binary (`user_version > TARGET_VERSION`), `SchemaMigrator` logs a warning and marks `schemaAhead = true`.
  - **The older binary NEVER drops, alters, or destroys unfamiliar tables or columns.** It continues normal operation and fails open.
- **Safe Downgrade**:
  - Rolling back a binary from version N to version N-1 is non-destructive.
  - Prior versions ignore newer additive columns (`estimator`, `schema_version`) and continue querying known columns safely.

### Database Integrity Check & Repair

If an abnormal system shutdown or OS crash leaves a dirty SQLite journal:

1. **Verify Integrity**:
```bash
sqlite3 ~/.local/share/condense/condense.db "PRAGMA integrity_check;"
```
Expected output: `ok`

2. **Checkpoint WAL**:
```bash
sqlite3 ~/.local/share/condense/condense.db "PRAGMA wal_checkpoint(TRUNCATE);"
```

3. **Rebuild Corrupted Database (Last Resort)**:
If SQLite integrity check fails due to disk hardware failure:
```bash
mv ~/.local/share/condense/condense.db ~/.local/share/condense/condense.db.corrupt
# The next condense invocation will recreate a fresh, schema-migrated database automatically
condense --version
```

---

## 4. Disaster Recovery Drills

Condense enforces automated rollback verification in CI via `DatabaseRollbackDrillTest`:
- Simulates schema forward-migration to target version.
- Populates records across all tables.
- Simulates backward compatibility under older binary assumptions.
- Asserts zero data loss and `PRAGMA integrity_check` returns `"ok"`.
