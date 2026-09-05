# Project discovery

`condense discover` looks at exact repository manifests and lockfiles and **recommends** existing filter definition names. It does not filter output, rewrite argv, install hooks, or write `filters.toml`.

```
condense discover
condense discover --format json
condense discover --root apps/web
```

`condense pytest` still routes by argv. Discovery is off the proxy path, so it never changes a child's exit code.

## What it prints

Text lists detected families, the recommended definition names, and probe/byte counters. JSON is schema version 1 (`schema_version`, `root`, `families`, `recommend`, `files_probed`, `files_read`, `bytes_read`, `truncated`, `warnings`, `error`).

Empty repositories and skipped I/O still exit 0. `--root` above the workspace, or a missing directory, exits 1.

## How matching works

Builtin rules live in `classpath:discover/*.toml`, enumerated by `discover/index.toml` only. Runtime never walks that directory. Maven `process-classes` runs `DiscoverDefinitionValidator` so a `recommend` name that is missing from `filters/index.toml`, a duplicate `priority` in the same `family`, or a glob/`..` path fails the build.

Each rule has `name`, `family`, `priority` (unique per family; **lower wins**), exact relative `signals`, optional `[[extras]]` (`path` plus optional `contains` needles on a capped UTF-8 prefix), and `recommend`. There is no content regex.

A signal matches when the contained path exists as a regular file. `prisma/schema.prisma` is one exact relative path, not a walk of `prisma/`. The git family uses the workspace `.git` marker already found by `resolveWorkspaceRoot`.

## Path safety and caps

The workspace root is the nearest `.git` ancestor (the existing bounded walk, no symlink follow) or the current directory. `--root DIR` may only **narrow** that root — the same contract as `condense read`.

Every candidate is `root.resolve(relative)` then `SafePathValidator.contain`. A symlink escape or a path outside the root is skipped (counted, not fatal). Missing files are normal.

Hard caps (Java, not TOML). The approved Phase 15 plan said 32 path probes; **64 is the shipped contract**.

| Cap | Default |
|---|---|
| Path probes | 64 |
| Files whose contents are read | 8 |
| Bytes per file | 64 KiB |
| Total bytes read | 256 KiB |

Hitting a cap stops further probes, sets `truncated: true`, and still returns what was found. Content reads take a bounded prefix (`readNBytes` of `min(maxBytesPerFile, remainingTotal)`), never the whole file. `NativeDiscoverIT` asserts `files_read ≤ 8`.

## What it does not do

It does not change `StrategyRegistry` lookup. It does not apply recommendations. It does not mine agent transcripts. It does not walk the tree for `*.csproj` or other globs. User-global discover overrides are not loaded.

Phase 16 may take this report and **propose** a project `filters.toml` diff. That is not this command.

## MCP

The read-only `discover` tool returns the same schema-1 JSON. Optional argument: `root`. `isError` only on bad arguments or a widening root, not on “nothing detected.”

## Native proof

`NativeDiscoverIT` (never skip) copies a committed fixture (`pnpm-lock.yaml` + `prisma/schema.prisma`) into an isolated workdir and runs the native `condense discover --format json`. It asserts recommended names and that counters stay under the caps. `NativeMcpIT` requires `tools/list` to include `discover`.
