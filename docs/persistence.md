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

WAL plus `busy_timeout` is the multi-process contract: two agent sessions can share one database. One `TrackingRepository` instance is still single-threaded. Analytics `insert` retries `SQLITE_BUSY` / `SQLITE_LOCKED` only. `SQLITE_READONLY` is not retried. Lost writes stay fail-open (they never change a child exit code) and are counted in `{dataDir}/write-failures.json` so `condense doctor` can see them after a restart. Schema target stays 2.

## Schema version

`PRAGMA user_version` is the only version clock. This binary targets **2**.

Version 1 keeps the historical `commands` table unchanged and adds `filter_outcomes`. Version 2 adds hook audit tables. Existing v0 and v1 files step through each missing version; the migrator does not jump from v1 to target without `applyV2`.

| Table | Purpose |
|---|---|
| `commands` | One row per proxied command (token ledger for `condense gain`) |
| `filter_outcomes` | Fail-open incidents only (`stage_exception`, `apply_fallback`) |
| `hook_events` | Install / remove / backup / verify / tamper rows (`ts` is epoch seconds) |
| `hook_baselines` | SHA-256 of each Condense-owned hook script |

A database written by a newer binary (`user_version > 2`) is not migrated. Reads of `commands` still try; outcome and hook writes fail-open. `condense doctor` reports `schema_ahead`.

Historical `raw_tokens` / `out_tokens` are never rewritten. Rows from before `utf8_weighted_v1` can sit next to new rows; `gain` publishes how **new** counts are produced.

## Retention

90 days, not configurable. On every successful open:

- `DELETE FROM commands WHERE ts < cutoff`
- `DELETE FROM filter_outcomes WHERE ts < cutoff`
- `DELETE FROM hook_events WHERE ts < cutoff`
- Bounded sweep of `{dataDir}/tee` and `{dataDir}/backups` (max 256 unlinks, no symlink follow, regular files one level deep)

`condense uninstall --purge` may delete `tee/`, `backups/`, and `{configDir}/filters.toml`. Those names are on the allowlist.

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

`persistence_write_failures` is the count from `{dataDir}/write-failures.json` (0 when the file is missing). `persistence_write_last_error` is the last recorded SQLite message, or omitted / `null`. Native proof asserts the field is present.

`hook_events` is the last 20 rows from the existing `hook_events` table (newest first). `hook_event_count` remains the full count. After `condense init --tool cursor`, native doctor JSON has a non-empty `hook_events` array.

Native proof: `NativePersistenceIT` creates a v0 file at runtime, runs the native binary, and asserts `user_version` equals the target, WAL, surviving seed row, and doctor JSON. A separate case seeds v1 and asserts `hook_events` exists after the binary opens the file. `NativeHookIT` repeats the v1→v2 proof with isolated hook homes.
