# Persistence

Condense stores local analytics in SQLite at `{dataDir}/condense.db`. There is no telemetry and no network I/O on this path.

Override the location with `CONDENSE_DATA_DIR`. Default data dirs are documented in the README.

## Connection

Every open uses the direct-driver pattern that made native-image persistence work:

```java
java.sql.Driver driver = new org.sqlite.JDBC();
connection = driver.connect(url, new java.util.Properties());
```

`DriverManager` is not used. After connect, Condense applies:

1. `PRAGMA busy_timeout = 5000`
2. `PRAGMA journal_mode = WAL` (fail-open if the filesystem cannot do WAL)
3. Forward-only schema migration
4. Retention prune

WAL plus `busy_timeout` is the multi-process contract: two agent sessions can share one database. One `TrackingRepository` instance is still single-threaded.

## Schema version

`PRAGMA user_version` is the only version clock. This binary targets **1**.

Version 1 keeps the historical `commands` table unchanged and adds `filter_outcomes`:

| Table | Purpose |
|---|---|
| `commands` | One row per proxied command (token ledger for `condense gain`) |
| `filter_outcomes` | Fail-open incidents only (`stage_exception`, `apply_fallback`) |

A database written by a newer binary (`user_version > 1`) is not migrated. Reads of `commands` still try; outcome writes fail-open. `condense doctor` reports `schema_ahead`.

Historical `raw_tokens` / `out_tokens` are never rewritten. Rows from before `utf8_weighted_v1` can sit next to new rows; `gain` publishes how **new** counts are produced.

## Retention

90 days, not configurable. On every successful open:

- `DELETE FROM commands WHERE ts < cutoff`
- `DELETE FROM filter_outcomes WHERE ts < cutoff`
- Bounded sweep of `{dataDir}/tee` (max 256 unlinks, no symlink follow, files contained in `tee/` only)

`condense uninstall --purge` may delete `tee/` and `{configDir}/filters.toml`. Those names are on the allowlist.

## `condense doctor`

Diagnoses persistence, hooks, trust, overrides, and empty `gain`.

```bash
condense doctor
condense doctor --format json
```

Exit 0 when the store is usable, including “zero rows, and here is why.” Exit 1 only when connect or migrate failed.

`empty_tracking_reason` is exactly one of:

| Value | Meaning |
|---|---|
| omitted / `null` | Rows exist in this data dir |
| `no_database` | `condense.db` was missing before doctor opened it |
| `zero_rows` | File is healthy and empty; hooks are present |
| `hooks_absent` | File is healthy and empty; no managed hook installed |
| `unreadable` | Connect failed |
| `migrate_failed` | Schema migration failed |
| `degraded` | Writes failed after a usable open |

Native proof: `NativePersistenceIT` creates a v0 file at runtime, runs the native binary, and asserts `user_version = 1`, WAL, surviving seed row, and doctor JSON.
