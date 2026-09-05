# Adaptive proposals

`condense propose` turns a discover report and local analytics into a **reviewable** project override diff. It never changes runtime filtering.

```
condense propose
condense propose --format json
condense propose --root apps/web
condense propose --write
```

`--write` creates `.condense/filters.toml.proposed` only. There is no `--apply`. Copy the sidecar to `.condense/filters.toml` yourself, then `condense config trust`. Until that review, `FilterOverrideLoader` still opens only `filters.toml`, so a `.proposed` file cannot alter a proxied command.

## What it prints

Text lists discover names and each proposal’s kind, status, command, and capability. JSON is schema version 1 (`schema_version`, `root`, `discover_recommend`, `analytics_unavailable`, `truncated`, `warnings`, `error`, `proposals`).

Each proposal has a stable `id` (12-char SHA-256 of kind, command, and TOML), `kind`, `status`, `command`, `required_capability`, `toml`, `evidence`, before/after stage names, and token counts from an in-memory replay.

The same database snapshot, tree, and catalog produce the same ids and TOML.

## Closed rules

Thresholds are Java constants (`ProposeLimits`), not TOML.

| Kind | When | Ready fragment |
|---|---|---|
| `coverage` | Discover recommended a definition | Copy that builtin’s `stages` onto each of its command prefixes |
| `safety` | This workspace has ≥ 3 `stage_exception` / `apply_fallback` rows for a command | `stages = []` (identity) |
| `unmatched` | A first non-flag token has no catalog family, ≥ 5 runs, and ≥ 2000 raw tokens | `ansi_strip` + `tail_lines` (40, skip blank) |

A builtin that uses `select_input` or `[gate]` is `blocked_not_representable` — those keys are illegal in user overrides. A coverage copy that fails shipped `[[tests]]` is `blocked_inline_test`. Existing project keys are `skipped_existing` and are never overwritten.

Analytics reads at most 500 command rows and 500 incident rows from the last 90 days, scoped by `cwd` under the discover root. Missing or unreadable analytics still emit coverage and set `analytics_unavailable`. Propose does not create `condense.db` just to look.

Schema target stays **2**. Proposals are computed, not stored.

## What it does not do

It does not sit on the proxy path. `ProxyService`, `StreamingProxy`, `StrategyRegistry`, and `FilterOverrideLoader` do not import this package.

It does not write `.condense/filters.toml` or user-global `filters.toml`. It does not pin trust hashes or grant capabilities. It does not mine agent transcripts or change builtin TCB definitions.

## MCP

The read-only `propose` tool returns the same schema-1 JSON. Optional argument: `root` (narrow-only). A `write` argument is `isError`. Empty proposals are a successful tool call.

## Native proof

`NativeProposeIT` (never skip) copies the pnpm+prisma fixture, plants unmatched analytics, and runs the native `condense propose --format json`. It asserts coverage and unmatched proposals, that live `filters.toml` is absent, and that `--write` creates only `filters.toml.proposed`. `NativeMcpIT` requires `tools/list` to include `propose`.
