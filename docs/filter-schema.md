# Filter schema v1

Builtin pipelines and user overrides share one schema. Data is interpreted by a hardcoded `StageFactory` switch. A TOML file cannot name a Java class. A leftover builtin definition **can** register commands: if none of its `commands` already belong to a `@CommandFilter` bean, `StrategyRegistry` constructs a `CatalogBackedFilter` for that definition name. Existing Java-backed rows stay on their beans. A `gate` key in a user override is still an unknown-key reject.

## Two document types

**Builtin** — `condense/src/main/resources/filters/<name>.toml`, enumerated by `filters/index.toml`:

```toml
schema_version = 1
name = "npm-install"
commands = ["npm install", "npm ci", "npm i"]

[[stages]]
strategy = "ansi_strip"

[[stages]]
strategy = "npm_install_summary"

[[tests]]
id = "packages-and-vulns"
input = """
added 12 packages in 3s
found 3 vulnerabilities (1 critical)
"""
expected = "✓ npm install: 12 packages | found 3 vulnerabilit"
```

**Override** — project `.condense/filters.toml` or user-global `filters.toml`:

```toml
schema_version = 1

[filters."npm install"]
stages = [
  { strategy = "ansi_strip" },
  { strategy = "tail_lines", max_lines = 20, skip_blank = true }
]
```

Overrides do not carry `name` or `[[tests]]`. `stages = []` replaces the default with an identity pipeline; it does not merge.

`schema_version = 1` is required. Unknown keys are rejected. Errors include a dotted path, and Jackson line/column when the parser (or a source scan fallback) can locate the key.

Streamability is **not** a schema field. Each Java stage declares `order_local`, `windowed`, `finalize_only`, or `document`. The runner derives STREAM vs CAPTURE from those declarations. A TOML override that swaps in `grouping` or another document stage becomes CAPTURE automatically. See [streaming.md](streaming.md).

`CondenseConfig` still uses a separate mapper that ignores unknown keys. Filter documents use `DefinitionMappers.STRICT_TOML`.

## Precedence

1. Project `.condense/filters.toml` (untrusted until TOFU or a valid CI hatch; fail-open on invalid TOML; capability ceiling always applies)
2. User-global `filters.toml` in the config directory (trusted by location; fail-open; no capability ceiling)
3. Builtin `classpath:filters/<name>.toml` via `BuiltinDefinitionCatalog` (fail-closed; TCB)

Project overrides that are untrusted, hash-changed, or above the granted capability class are skipped. Review is `condense config trust`. See [trust.md](trust.md).

`PythonFilter` is a Java router and has no TOML file.

## Index rule

Runtime loads **only** names listed in `filters/index.toml`, each via an exact resource path. It never walks a classpath directory. Graal includes `filters/.*\.toml` for `getResource`, not for directory listing.

The Maven `process-classes` validator (`BuiltinDefinitionValidator`) asserts index ↔ files on disk. Surefire architecture tests require every Java `PipelineBackedFilter.definitionName()` and `@CommandFilter` prefix to have a matching builtin row. Leftover index names have no Java class; `CorpusCoverageTest` requires every index name to have a corpus row.

## Fail-closed vs fail-open

| Surface | Invalid document |
|---|---|
| Builtin index + definition files | Fail the build (`process-classes`) and fail catalog load |
| User override | Warn and fall through to the next tier. `condense config validate` exits 1 |
| Inline `[[tests]]` failure | Fail the **build**, not a proxied command |

Override files without `schema_version` fail-open at runtime.

## Stage vocabulary

Generic aliases (canonical snake_case; hyphen/short aliases exist for the original six):

| Strategy | Parameters |
|---|---|
| `ansi_strip`, `tree_compression`, `json_structure` | none |
| `deduplication` | `window_size` (1–10000, default 50) |
| `grouping` | `pattern` (≤500 chars, ≥1 group), `include_other` |
| `state_machine` | `initial_state`, `transitions` (≤50), `default_actions` |
| `tail_lines` | `max_lines`, `skip_blank`, `header_only_when_truncating` |
| `head_tail` | `head`, `tail` |
| `aggregate_by_key` | `key` in `{prefix_before_colon, file_extension}`, `header` (`{lines}`, `{keys}`), `top_n` |
| `regex_capture` | `pattern`, `format` (`$1`, `$0`), `fallback` |
| `git_status`, `json_lines`, `docker_ps` | none |

Named command-specific aliases (no user params; trusted Java):  
`git_add_summary`, `git_commit_summary`, `git_diff_summary`, `git_log`, `git_push_summary`, `ls_empty_tree_fallback`, `cat_content`, `docker_build_summary`, `kubectl_dispatch`, `cargo_clippy_summary`, `cargo_install_summary`, `cargo_test_summary`, `gradle_summary`, `make_summary`, `mvn_summary`, `eslint_json`, `eslint_text`, `jest_summary`, `npm_install_summary`, `tsc_summary`, `vitest_summary`, `golangci_summary`, `pip_install_summary`, `pytest_summary`, `ruff_summary`.

User overrides may use any alias in v1. Project files still need a matching capability grant (`reduce` / `reshape` / `rewrite`). See [trust.md](trust.md).

## Builtin-only optional keys

These fields are valid on `classpath:filters/<name>.toml` only. They are unknown keys in user `filters.toml`.

| Key | Values / fields |
|---|---|
| `select_input` | `stdout_or_stderr` (default), `stderr_then_stdout`, `stdout`, `stderr` |
| `[gate]` | `passthrough_verbose`, `passthrough_max_lines`, `passthrough_nonzero_exit` (all default off) |

## Adding a definition

Prefer a leftover catalog definition (no Java class):

1. Write `src/main/resources/filters/<name>.toml` (`schema_version = 1`, unique `name`, `commands` that are not already claimed by a `@CommandFilter`, `[[stages]]` using existing aliases, ≥1 `[[tests]]`).
2. Append the name to `filters/index.toml`.
3. Add corpus fixtures, a `catalog.json` row, and a golden lock. See [fidelity-corpus.md](fidelity-corpus.md) and [CONTRIBUTING.md](../CONTRIBUTING.md).

Add a `PipelineBackedFilter` with `@CommandFilter` only when you need a handwritten gate, a new `StageFactory` alias, or a router. Do not override `buildPipeline()`. New Java is otherwise needed only when `StageFactory` lacks a stage.
