# Structured diagnostics IR

Filtered output is a **typed document** plus renderers, not 32 ad-hoc formatters. Default CLI output stays compact **text** from `FilterResult.output()` (the pipeline text). Exemplar stages call `TextRenderer` internally; `TextRenderer.render(document)` is not the default CLI path. Identity against `apply().output()` is locked for **7** corpus IDs until remediation R23. `condense --format json <command>` prints one schema-1 JSON object after the child exits. `condense explain --format json` embeds the same records on `document`. Token savings stay on the text path (semantic savings, R25, is deferred).

The MCP `run` tool returns this envelope. It must not invent a second model. See [docs/mcp.md](mcp.md).

## Envelope

```json
{
  "schema_version": 1,
  "kind": "test",
  "command": "pytest",
  "filter": "PytestFilter",
  "child_exit_code": 1,
  "was_filtered": true,
  "provenance": { "applied": true, "stamp": "condense[filtered]" },
  "document": { }
}
```

`provenance` is the same `{ applied, stamp }` object as `condense explain`. JSON is never stamped as a fake first line.

Unknown keys fail on parse. `kind` is a closed set — renderers switch on it; there is no reflective discovery.

## Kinds

| `kind` | Producers | Payload |
|---|---|---|
| `test` | `pytest` | cases (`name`, `status`, `detail`) + counts |
| `diagnostic` | `eslint` / `npx eslint` | findings + grouped counts (text-path findings are empty until R22; JSON still groups) |
| `dependency` | `npm install` / `npm ci` / `npm i` | added packages, vulnerability text, irrevocable warn/err lines (capped at 20) |
| `resource` | `docker ps` | column-stable rows |
| `opaque` | everyone else, gates, IR-build failure | `body` = the filtered or passthrough text **without** inventing structure |

Unmigrated commands always have a document. Missing JSON is a bug.

## CLI

Options must come **before** the proxied command, same as `-u`:

```
condense --format json pytest
condense --format json npm install
condense explain --format json --input fixture.txt --exit-code 1 pytest
```

`--format json` never live-prints fragments. STREAM pipelines (`npm install`) still build the document while the child runs; JSON waits until exit, then prints one object. Default text streaming is unchanged.

Ultra-compact applies to the **text** renderer only.

## Token accounting

The default text path is unchanged (`FilterResult.of` on rendered text). `--format json` counts the JSON the agent actually sees as `out_tokens`. Corpus floors stay on the text path.

## Fail-open

If IR construction throws, Condense records a `ir_fallback` incident and emits `kind=opaque` from the last good text (or passthrough). The child exit code does not change.

## Native proof

`NativeIrIT` (never skip) runs a PATH-stubbed `pytest` and `npm install` through the shipped binary with `--format json`, and checks `condense explain --format json --input <pytest fixture> pytest` for `document.kind=test`. ESLint, `docker ps`, and leftover `kind=opaque` (for example `git status`) join that pack in R24.
