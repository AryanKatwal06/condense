# Proxy reliability contract

Condense is a proxy. The child’s observable status and the bytes already captured win. Condense diagnostics are extra provenance-marked lines. This file is the human form of `condense/src/test/resources/reliability/failure-contract.json`. `FailureContractCatalogTest` fails `mvn test` if any catalog `id` lacks a mapped `@Test` method.

## Exit codes

| Code | Meaning |
|---|---|
| Child `exitValue()` | The process was reaped and Condense did not invent a status. Drain faults, a broken consumer pipe, and filter/analytics failures must not replace this. |
| `-1` | Condense destroyed the child or could not reap a status. Used for timeout, output cap when the child was killed, drain failure when no status is available, and JVM shutdown (`DESTROYED`). |
| `1` from Condense itself | Launch error only. Empty argv, self-proxy refusal, and `ProcessBuilder.start()` failure. Not used for stream I/O faults after the child has started. |

`ExecutionResult.termination()` is a closed enum: `CHILD_EXIT`, `TIMEOUT`, `OUTPUT_CAP`, `DRAIN_ERROR`, `DESTROYED`. It is never inferred from `-1` alone. Schema-1 JSON may include `termination` on the document envelope; it is omitted for a normal `CHILD_EXIT`.

## Failure modes

### Drain I/O

If a stdout or stderr drain thread fails after the child has started, Condense keeps the partial capture files, appends one of

- `condense: stdout drain failed`
- `condense: stderr drain failed`

to the **stderr** capture (append, never replace stdout or prior stderr), and returns the child’s exit code when reaped, else `-1`, with `DRAIN_ERROR`. `execute` does not throw for stream I/O.

### Timeout

`CONDENSE_COMMAND_TIMEOUT_SEC` as a positive integer, or an explicit duration passed to `execute`. After `destroyForcibly` of the child tree, drain threads are joined, then

```
condense: command timed out after Ns
```

is **appended** to the existing stderr file. Bytes the child already wrote on stdout and stderr stay. Exit `-1`, reason `TIMEOUT`.

### Output cap

Each stream is capped at 10 MB. Condense stops capturing, destroys the child tree, prints `condense: output capped at 10MB` on the live stderr for STREAM/LIVE_RAW, and keeps the child’s exit if it was already reaped, otherwise `-1`. Reason `OUTPUT_CAP`.

### Invalid UTF-8

Capture files are decoded as UTF-8 with replacement, same policy as `utf8_weighted_v1`. Malformed bytes must not become an empty `combined()` when the file has content.

### Line cap

`Utf8LineDecoder` caps the current line at 1 MiB characters (`Utf8LineDecoder.MAX_LINE_CHARS`). Exceeding the cap emits the truncated line, resets the builder, and continues. It does not throw. The 10 MB stream cap remains the process-level backstop.

### Self-proxy

Condense refuses to spawn itself. The first argv token is compared by file name (absolute, relative, `PATH` / `PATHEXT`) and against `ProcessHandle.current().info().command()` for `condense`, `condense.exe`, `condense-runner`, and `condense-runner.exe`. Refusal throws `IllegalStateException` **before** spawn (launch error, exit 1). Unrelated commands such as `git` are not blocked.

### Broken consumer pipe

If the agent’s stdout `PrintStream.checkError()` is true, Condense stops live-printing but keeps draining the child into capture files. The child exit is unchanged. Lost live lines are consumer disappearance, not a proxy failure.

### Shutdown

A `ShutdownEvent` observer destroys active children and descendants. `execute` returns `-1` / `DESTROYED` instead of hanging.

## Native proof

`NativeReliabilityIT` (never skip) covers timeout with prior stderr, the cap banner, and proxied `exit 7` staying 7.
