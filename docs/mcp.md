# MCP Server

Agents can call Condense as **tools and resources** over stdio instead of rewriting shell commands through hooks. **MCP is the preferred agent path.** Hook install (`condense init`) is the fallback. There is no HTTP, SSE, Streamable HTTP, or OAuth transport.

```bash
condense mcp                      # Print generic MCP client config snippet
condense mcp --start              # Speak MCP protocol on stdin/stdout
condense mcp -c <client>          # Print tailored configuration snippet for specific client
condense mcp --list-clients       # List all 10 first-class supported agent hosts
```

## First-Class Client Configurations

Condense provides first-class, client-tailored configuration snippets and config paths:

| Client Identifier | Host Application | Standard Config File Path | Configuration Schema Format |
|---|---|---|---|
| `claude-desktop` | Claude Desktop | `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS)<br>`%APPDATA%\Claude\claude_desktop_config.json` (Win) | `mcpServers.condense` |
| `claude-code` | Claude Code CLI | `~/.claude/settings.json` (global)<br>`./claude.json` (project) | `mcpServers.condense` |
| `cursor` | Cursor | `~/.cursor/mcp.json` (macOS/Linux)<br>`%USERPROFILE%\.cursor\mcp.json` (Win) | `mcpServers.condense` |
| `windsurf` | Windsurf / Codeium | `~/.codeium/windsurf/mcp_config.json` | `mcpServers.condense` |
| `cline` | Cline | `~/Documents/Cline/mcp_settings.json` | `mcpServers.condense` |
| `zed` | Zed Editor | `~/.config/zed/settings.json` | `context_servers.condense` |
| `vscode` | VS Code / Copilot | `.vscode/mcp.json` | `servers: [{ name, command, args }]` |
| `opencode` | OpenCode | `~/.config/opencode/config.json` | `mcp.servers.condense` |
| `antigravity` | Google Antigravity / Gemini | `~/.gemini/antigravity-ide/mcp_config.json` | `mcpServers.condense` |
| `generic` | Generic MCP Host | Standard JSON config | `mcpServers.condense` |

To generate the exact JSON snippet for your editor:
```bash
condense mcp --client cursor
condense mcp --client zed
condense mcp --client vscode
```

## Dated MCP Client Compatibility Matrix (September 2026)

| Host Client | Protocol Version | Transport | Tested Version | Status as of Sep 2026 |
|---|---|---|---|---|
| **Claude Desktop** | `2024-11-05`, `2025-03-26` | stdio | v0.8.x+ | Production |
| **Claude Code** | `2024-11-05`, `2025-03-26` | stdio | v1.0.x+ | Production |
| **Cursor** | `2024-11-05`, `2025-03-26` | stdio | v0.45.x+ | Production |
| **Windsurf** | `2024-11-05`, `2025-03-26` | stdio | v1.4.x+ | Production |
| **Cline** | `2024-11-05` | stdio | v3.2.x+ | Production |
| **Zed** | `2024-11-05`, `2025-03-26` | stdio | v0.170.x+ | Production |
| **VS Code / Copilot** | `2024-11-05`, `2025-03-26`, `2025-06-18` | stdio | v1.98.x+ | Production |
| **OpenCode** | `2024-11-05`, `2025-03-26` | stdio | v1.2.x+ | Production |
| **Google Antigravity** | `2024-11-05`, `2025-03-26`, `2025-06-18` | stdio | v2.0+ | Production |
| **Generic MCP Host** | Any accepted version | stdio | RFC | Production |



## Transport

JSON-RPC 2.0, one object per newline, no embedded newlines (MCP spec 2025-03-26 transports). Logs go to **stderr** (`quarkus.log.console.stderr=true`). Process stdout is only JSON-RPC lines.

Accepted protocol versions: `2024-11-05`, `2025-03-26`, `2025-06-18`. The server echoes the client's version when it is in that set; otherwise it replies `2024-11-05`. It advertises `tools` and `resources` only.

Closed methods: `initialize`, `notifications/initialized`, `tools/list`, `tools/call`, `resources/list`, `resources/read`, `ping`. Anything else is JSON-RPC `-32601`. A request that is not `notifications/*` and has a missing or null `id` is JSON-RPC `-32600`. `notifications/initialized` (and other `notifications/*` methods) stay no-response. Bare `condense mcp` lists `run`, `explain`, `read`, `discover`, and `propose`.

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
| `propose` | `{ "root"?: path }` | Existing `ProposeReport` (schema 1). Reviewable override diffs; does not write `filters.toml`. |

`command` is an **argv array**. A single shell string is refused. There is no `cwd` override and no MCP `--stdin` (stdio is the protocol).

`run` uses the same `ProxyService` engine as the CLI. Child exit code is `document.child_exit_code`. The MCP tool itself is not a shell.

`ultra_compact` changes text inside the document, not the JSON-RPC envelope.

## Resources

| URI | Same JSON as |
|---|---|
| `condense://gain` | `condense gain --format json` |
| `condense://gain/trend` | `condense gain --trend --format json` |
| `condense://doctor` | `condense doctor --format json` |

## Path safety

MCP-supplied filesystem paths (`read.path`, `explain.input`) go through `ReadPathGate.openFile`. `discover.root` uses `ReadPathGate.resolveNarrowRoot` — the same narrow-only workspace contract, without read's byte-max or binary file checks. File probes still use `SafePathValidator.contain`. `discover.root` may only narrow. Escape is a tool error and does not leak file bytes.

CLI `condense explain --input` is unchanged.

## Native proof

`NativeMcpIT` (never skip) drives `condense mcp --start` on the GraalVM binary: initialize + `run` on PATH-stubbed pytest, contained vs escaped `read`, `tools/list` including `discover` and `propose`, and `condense://gain`. `NativeDiscoverIT` runs `condense discover` on a fixture tree. `NativeProposeIT` runs `condense propose` on the same style of fixture. `NativeHookIT` also sends initialize + `tools/list` so hook work cannot regress the preferred path.
