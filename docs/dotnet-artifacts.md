# .NET sidecar artifacts

Condense can read three structured .NET artifacts when they already exist or when it can inject a **collision-safe** extra logger. Console text stays the fail-open fallback. There is no Java `@CommandFilter` host; leftover catalogs (`dotnet-test`, `dotnet-build`, `dotnet-restore`, `dotnet-format`, `msbuild`) prefix the new stages.

See [filter-schema.md](filter-schema.md), [ir.md](ir.md), and [machine-output.md](machine-output.md).

## What is injected

`SidecarArgvPolicy` runs once in `ProxyService` after filter lookup and before the stream/batch split. Lookup, analytics, explain, and hooks keep the original argv. The rewritten list is what both launch paths execute.

| Prefix | Injected when absent | Collision (no inject) |
|---|---|---|
| `dotnet build`, `dotnet test`, `dotnet restore`, `dotnet msbuild`, `msbuild` | `-bl:<temp>/msbuild.binlog` | `-bl`, `-bl:`, `/bl`, `/bl:`, `--binaryLogger` (any case) |
| `dotnet format` | `--report <temp>` | `--report` / `--report=` |

Help (`--help`, `-h`, `-?`, `/?`) and a policy fault launch the original argv. Flags after `--` are not inspected and injection is inserted before `--`.

The sidecar directory is `Files.createTempDirectory("condense-dotnet-")` under the JVM temp dir. `SafePathValidator.isKnownCondenseTemp` recognizes that prefix so orphan sweep / uninstall can collect stragglers. Cleanup runs after apply and is fail-open.

User-named binlog or report paths are parsed when they pass `containReadable` against the workspace or the sidecar parent. Condense never overwrites them and never injects a second logger.

## What is never injected

`--logger trx`, `--logger "trx;..."`, `--report-trx`, and `--report-trx-filename`. A Microsoft.Testing.Platform project can fail the child if Condense forces a VSTest TRX logger. That is not argv-safe.

TRX is parsed when the user already named a file, or when `./TestResults` already exists. At most 8 workspace-contained `*.trx` files, 8 MiB each. There is no repo-wide walk.

## Formats

| Artifact | Stage | Success IR |
|---|---|---|
| MSBuild `.binlog` (v18–27, gzip + 7-bit records) | `msbuild_binlog` | `kind=diagnostic`, `tool=msbuild` |
| VSTest / MTP `.trx` XML | `trx_report` | `kind=test`, `tool=trx` |
| `dotnet format --report` JSON array | `format_report` | `kind=diagnostic`, `tool=dotnet-format` |
| Human console (no readable artifact) | existing grouping / `tail_lines` | `kind=opaque` |

Detect rules are fail-open. A miss leaves the text for the next stage. The child exit never changes.

### `msbuild_binlog`

Header is int32 LE file version plus int32 LE minimum reader version, then `GZIPInputStream`. Versions 18–27 are supported. Older versions record `binlog_version` and continue. Newer writers are best-effort only when the minimum reader is in 18–27.

Unknown record kinds are skipped by length. Embedded zip archives (record kind 17) are **never unpacked**.

Kept fields are error and warning events: `code`, `file`, `line`, `column`, `message`, `projectFile`.

**Caps (hard constants).** 32 MiB decompressed; 50_000 records; 100_000 string-table entries; 1 MiB per string; 500 findings. On cap, keep the prefix and append `condense: msbuild_binlog capped`.

### `trx_report`

JDK StAX with `SUPPORT_DTD=false` and `IS_SUPPORTING_EXTERNAL_ENTITIES=false`. Files that declare `<!DOCTYPE` or `<!ENTITY` record `trx_xxe` and are skipped. Local names `Counters`, `UnitTestResult`, `ErrorInfo`, `Message`, and `StackTrace` work with the empty xmlns and `http://microsoft.com/schemas/VisualStudio/TeamTest/2010`.

Multi-file merge sums counters and keeps the first 50 Failed/Error results. Passed bodies are skipped. Stack traces are clipped to 8 KiB. Truncated XML keeps last-good failures when any were seen.

**Caps.** 8 files; 8 MiB/file; element depth 32; 2_000 results scanned.

### `format_report`

A JSON array whose objects have `FilePath` / `FileName` / `FileChanges`. Exact PascalCase fields. An empty array renders `dotnet-format: ok`. Objects, NDJSON, human text, depth `> 8`, or size `> 1` MiB continue. Cap 200 findings; append `condense: format_report capped`.

## Combined streams

`dotnet-test`, `dotnet-build`, `dotnet-format`, and `msbuild` set `select_input = stdout_then_stderr` so compiler errors on stderr stay next to stdout.

## Native proof

`NativeDotnetIT` (never skip) PATH-stubs `dotnet` / `msbuild` (`.cmd` on Windows). Artifact stubs write the fixture to the injected `-bl:` / `--report` path and assert `kind` `diagnostic` or `test`. Human typical stubs ignore those flags and stay `opaque`. A pre-existing user `-bl:` file is not overwritten. An XXE TRX stub does not leak `win.ini`. This Windows workspace does not build native images; the next Actions run after push is the native gate.
