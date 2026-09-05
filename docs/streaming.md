# Streaming filtering

Proxy mode can print filtered lines **while** a long command is still running. The 10 MB-capped temp-file capture is unchanged and still feeds tee, token counts, and fail-open replay.

## How the runner chooses a mode

There is no `--stream` flag and no TOML `streamable` key. Mode is derived from the Java stage classes in the resolved pipeline:

- **STREAM** — every stage is `order_local` or `windowed`. The first irrevocable filtered line is printed before the child exits.
- **CAPTURE** — any stage is `document` or `finalize_only`. Output waits until the child exits, then the same session engine runs on the captured text.
- **LIVE_RAW** — unmatched commands (`PassthroughStrategy`) print decoded stdout/stderr lines as they arrive, still writing the capture files.

An override that swaps a streamable summary for `grouping` becomes CAPTURE automatically.

`npm install` / `npm ci` / `npm i` and `docker build` are the first STREAM builtins. They emit irrevocable progress (`npm warn` / `#N DONE`) as it arrives, then the existing one-line success summary at exit. Failure with no irrevocable signal still passthroughs.

`condense --format json` does **not** live-print. STREAM pipelines still build the document while the child runs; JSON waits until exit and prints one object. Default text streaming is unchanged. See [docs/ir.md](ir.md).

## Timeouts and the 10 MB cap

The proxy waits until the child exits unless `CONDENSE_COMMAND_TIMEOUT_SEC` is a positive integer. Live `condense explain` (no `--input` / `--stdin`) uses the same `resolveProxyTimeout()` as the proxy. `CommandExecutor.execute(args)` without a duration still defaults to 60 seconds for tests.

If either stream exceeds 10 MB, Condense stops capturing, destroys the child, prints `condense: output capped at 10MB` to stderr, and keeps whatever exit code the child produced (or `-1` if it was killed). It does not replace that code with a generic error 1.

## Charset

`Utf8LineDecoder` holds incomplete UTF-8 sequences across 8 KiB drain chunks. `\r\n` is one line break. A lone `\r` resets the current line (progress bars) instead of emitting it.

## Explain

`condense explain --format json` reports `pipeline_mode` and per-stage `streamability`. Explain itself stays batch — it does not live-print the child.
