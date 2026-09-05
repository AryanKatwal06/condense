# MCP server

Agents can call Condense as **tools and resources** over stdio instead of rewriting shell commands through hooks. **MCP is the preferred agent path.** Hook install (`condense init`) is the fallback. There is no HTTP, SSE, Streamable HTTP, or OAuth transport.

```
condense mcp            # print a client config snippet; exit 0
condense mcp --start    # speak MCP on stdin/stdout
```

Claude Desktop (and any other MCP client) should launch:

```json
{
  "mcpServers": {
    "condense": {
      "command": "condense",
      "args": ["mcp", "--start"]
    }
  }
}
```

## Transport

JSON-RPC 2.0, one object per newline, no embedded newlines (MCP spec 2025-03-26 transports). Logs go to **stderr** (`quarkus.log.console.stderr=true`). Process stdout is only JSON-RPC lines.

Accepted protocol versions: `2024-11-05`, `2025-03-26`, `2025-06-18`. The server echoes the client's version when it is in that set; otherwise it replies `2024-11-05`. It advertises `tools` and `resources` only.

Closed methods: `initialize`, `notifications/initialized`, `tools/list`, `tools/call`, `resources/list`, `resources/read`, `ping`. Anything else is JSON-RPC `-32601`.

There is no official MCP Java SDK on the classpath. The handshake is a handful of Jackson records plus a hardcoded switch.

## Tools

Every tool result is `content: [{ "type": "text", "text": "<compact JSON>" }]`. The inner JSON is the same record `condense` already writes — not a parallel DTO.

`isError: true` only when Condense refused the call (bad arguments, path escape, missing file, launch failure). A child process that exits 1 is a **successful** tool call.

| Tool | Arguments | Result |
|---|---|---|
| `run` | `{ "command": ["pytest"], "ultra_compact"?: boolean }` | Schema-1 IR envelope (`docs/ir.md`). Same records as `condense --format json`. |
| `explain` | `{ "command": ["pytest"], "input"?: path, "exit_code"?: number, "ultra_compact"?: boolean }` | Existing `ExplainReport` (includes `document`). |
| `read` | `{ "path": "Src.java", "level"?: "verbatim\|comments\|outline", "ultra_compact"?: boolean }` | Existing `ReadReport` plus stamped body. |
| `discover` | `{ "root"?: path }` | Existing `DiscoverReport` (schema 1). Recommends definition names; does not filter. |

`command` is an **argv array**. A single shell string is refused. There is no `cwd` override and no MCP `--stdin` (stdio is the protocol).

`run` uses the same `ProxyService` engine as the CLI. Child exit code is `document.child_exit_code`. The MCP tool itself is not a shell.

`ultra_compact` changes text inside the document, not the JSON-RPC envelope.

## Resources

| URI | Same JSON as |
|---|---|
| `condense://gain` | `condense gain --format json` |
| `condense://doctor` | `condense doctor --format json` |

## Path safety

MCP-supplied filesystem paths (`read.path`, `explain.input`, `discover.root`) go through the same workspace containment as the CLI. `discover.root` may only narrow. Escape is a tool error and does not leak file bytes.

CLI `condense explain --input` is unchanged.

## Native proof

`NativeMcpIT` (never skip) drives `condense mcp --start` on the GraalVM binary: initialize + `run` on PATH-stubbed pytest, contained vs escaped `read`, `tools/list` including `discover`, and `condense://gain`. `NativeDiscoverIT` runs `condense discover` on a fixture tree. `NativeHookIT` also sends initialize + `tools/list` so hook work cannot regress the preferred path.
