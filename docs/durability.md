# Durable-state and chaos contract

Condense mutates trust, config, tee dumps, hook files, the write-failure ledger, and SQLite. A crash or injected fault must not replace a last-good user or third-party file with a prefix, and it must not change a proxied child exit. This file is the human form of `condense/src/test/resources/reliability/durable-fault-contract.json`. `DurableFaultCatalogTest` fails `mvn test` if any catalog `id` lacks a mapped `@Test` method.

Schema target stays **2**. Disk-full is injected at the `DurableIo` seam. Tests do not fill the runner disk.

## Fail-open vs fail-closed

| Path | Policy |
|---|---|
| Proxy (`ProxyService`) | Analytics, tee, and ledger faults leave the child exit and filtered stdout unchanged. |
| State-writing commands (`config trust`, `init`, config write, `propose --write`) | Fail closed **before** replacing the destination. The previous valid bytes stay. |
| Orphan sweep | Deletes only known Condense temps under validated config/data dirs. Never agent configs. Never `condense.db-wal` / `condense.db-shm`. |

## Seams

Production code does not call `java.nio.file.Files` for durable replacements. It goes through:

1. **`DurableIo`** — package-style seam (Phase 1 `ProcessIo` pattern). Production is `DurableIo.SYSTEM`. Tests throw after N bytes, fail rename, skip rename (kill between tmp and move), or refuse writes.
2. **`AtomicFile`** — unique temp in the **same directory** as the target, `SafePathValidator.contain(target, parent)`, write, optional `FileChannel.force(true)` (ignored if unsupported), `ATOMIC_MOVE` then fallback `REPLACE_EXISTING`. On failure the temp is deleted. The destination is never truncated first.
3. **`CondenseClock`** — wraps `java.time.Clock`, production `Clock.systemUTC()`. Retention cutoff, tee filenames, trust pin timestamps, analytics insert timestamps, and hook backup names use it. Proxy timeouts stay on wall-clock `System.nanoTime` / `currentTimeMillis`.

## SQLite

`TrackingRepository.connection()` uses `new org.sqlite.JDBC()` then `driver.connect`. `DriverManager` is forbidden on this path (native-image). After pragmas, `PRAGMA integrity_check` runs. If the result is not `ok`, the repository marks degraded, skips migrate, and the proxy stays fail-open. Concurrent opens of a v0 file rely on SQLite locks plus `busy_timeout = 5000`.

## Override cache

`FilterOverrideLoader` caches by path plus file `mtime` and `size` (and existence). A rewrite, create, or delete reloads. `invalidateCache()` remains for writers. `reasonFor` is `absent`, `empty`, `no_match`, `pipeline_build_failed`, or a trust skip (`hash_mismatch`, `capability`, `untrusted`).

## Doctor

`condense doctor --format json` names each degraded catalog mode as a `warnings[]` substring, an `empty_tracking_reason` value, or an existing JSON field (`schema_ahead`). Modes include leftover temps (`orphan_tmp`, `kill_between_tmp_and_rename`, `partial_*_write`), `ledger_unwritable`, `disk_full`, `readonly`, `rename_fail`, `corrupt_db`, `integrity_check_failed`, `lock_storm`, `concurrent_migrate`, `clock_jump`, `symlink_swap`, `pipeline_build_failed`, and `override_cache_mutation`.

## Native proof

`NativeChaosIT` (never skip) covers:

- corrupt `condense.db` still exits 0 on a proxied success
- schema-ahead doctor warning
- leftover `trust.json.tmp` with two sequential native processes still reading last-good `trust.json`
