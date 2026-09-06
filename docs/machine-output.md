# Machine-output parsing

Condense parses Terraform and OpenTofu **machine-readable** streams when the agent already asked the child for them. It does not rewrite argv. If the child printed human text, the existing grouping pipeline still runs.

This is leftover catalog work (`terraform`, `terraform-fmt`, `terraform-state`, `tofu`, `tofu-fmt`, `tofu-state`). There is no Java `@CommandFilter` host. `terraform state show` and other unmatched subcommands stay passthrough.

See [filter-schema.md](filter-schema.md) and [ir.md](ir.md). .NET sidecar artifacts (MSBuild binlog, TRX, `dotnet format` JSON) are documented in [dotnet-artifacts.md](dotnet-artifacts.md).

## Formats

| Child output | Stage | Success IR |
|---|---|---|
| NDJSON machine UI (`terraform plan -json`, apply, destroy, init) | `machine_ui` | `kind=resource`, `format=infra` |
| One `validate -json` object | `validate_json` | `kind=diagnostic`, `tool=validate` |
| `state list` addresses | `resource_graph` | `kind=resource`, `format=infra` |
| Human plan / init / validate text | grouping (after the structural stages `continueWith`) | `kind=opaque` |
| `fmt` file names, or empty fmt | `regex_capture` identity / `fmt: ok` | `kind=opaque` |

Detect rules are fail-open. A miss leaves the text for the next stage. The child exit never changes.

### `machine_ui`

Line-oriented. Each `{...}` line is parsed on its own with the already-registered Jackson mapper. Non-JSON lines that start with `error` or contain `error:` are kept as diagnostic rows so a JSON plan on stdout can still surface a stderr `Error:` when `select_input = stdout_then_stderr`.

**Detect.** At least one object with `type` plus (`@module` is `terraform.ui` **or** `type=version` with a `ui` field). Otherwise `continueWith`.

**Version.** UI major `0` or `1` is accepted. Major `≥ 2` records `machine_ui_version` and `continueWith` the raw text. A missing `version` event still parses if other UI events were seen.

**Keep.** `planned_change`, `resource_drift`, `change_summary`, error/warn `diagnostic`, `outputs` (names only; values omitted when `sensitive=true` or when a value is present), error/warn `log`.

**Drop.** Refresh/apply progress, lock chatter, unstructured info logs.

**Caps (hard constants).** 10_000 JSON objects; 1_000 resource rows; 200 diagnostics; 1 MiB per line. On cap, keep the prefix plus the last `change_summary` if seen, append `condense: machine_ui capped` on the text path, and do not throw.

**Malformed / truncated line.** Skip that line. If the scan produced no `change_summary` and no kept change or diagnostic, `continueWith` the raw text.

### `validate_json`

A single JSON object whose trimmed text starts with `{` and that has `valid` and/or `diagnostics`. NDJSON (more than one unindented `{` line), arrays, depth `> 8`, or size `> 1` MiB → `continueWith`. Clean `valid=true` with no diagnostics renders `validate: ok`.

### `resource_graph`

Generic address compressor. Phase 4 only wires `terraform state list` / `tofu state list`.

Detect: at least 2 non-blank lines and ≥ 80% match a conservative address regex (`module.` segments, optional `data.`, `type.name`, optional `["key"]` / `[n]`). `main.tf` is not an address (the type token must contain `_`, or the line must start with `module.` / `data.`).

Existing `StageDef` fields only: `key` (`resource_type` or `whole_line`), `header` (`{lines}` / `{keys}`), `top_n` (default 20), `max_lines` (default 2_000), `fallback`.

## What this does not do

- Inject `-json` or any other child argv.
- Parse `terraform show -json`, `tofu show -json`, or `terraform state show` (large, secret-bearing).
- Claim bare `terraform state` / `tofu state`, so `state show` stays unmatched.
- Change schema numbers. Filter and IR stay at **1**. Additive optional IR fields (`format`, plan counts, `tool`, address/action) are omitted when unused so docker/eslint JSON stays byte-stable.

## Combined streams

Plan/apply/destroy/init/validate leftovers set `select_input = stdout_then_stderr` (builtin-only). A JSON plan on stdout plus `Error:` on stderr keeps both. Default `stdout_or_stderr` would have dropped the error.

## Native proof

`NativeTerraformIT` (never skip) PATH-stubs `terraform` / `tofu`. JSON stubs assert `kind` `resource` or `diagnostic`; human `typical.txt` is `opaque`; `terraform state show` is unstamped passthrough. This Windows workspace does not build native images; the next Actions run after push is the native gate.
