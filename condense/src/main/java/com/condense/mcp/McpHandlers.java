package com.condense.mcp;

import com.condense.VersionProvider;
import com.condense.analytics.GainRepository;
import com.condense.core.Mappers;
import com.condense.core.ProjectFingerprint;
import com.condense.core.ProxyService;
import com.condense.core.TrackingRepository;
import com.condense.doctor.DoctorService;
import com.condense.explain.ExplainService;
import com.condense.ir.Document;
import com.condense.ir.JsonRenderer;
import com.condense.discover.DiscoverReport;
import com.condense.discover.DiscoverService;
import com.condense.read.ReadLevel;
import com.condense.read.ReadPathGate;
import com.condense.read.ReadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Closed switch for MCP methods and tool names. No reflective dispatch.
 */
@ApplicationScoped
public class McpHandlers {

    private final ProxyService proxy;
    private final ExplainService explain;
    private final ReadService read;
    private final GainRepository gain;
    private final DoctorService doctor;
    private final TrackingRepository tracking;
    private final DiscoverService discover;

    public McpHandlers() {
        this(null, null, null, null, null, null);
    }

    @Inject
    public McpHandlers(
            ProxyService proxy,
            ExplainService explain,
            ReadService read,
            GainRepository gain,
            DoctorService doctor,
            TrackingRepository tracking
    ) {
        this.proxy = proxy;
        this.explain = explain;
        this.read = read;
        this.gain = gain;
        this.doctor = doctor;
        this.tracking = tracking;
        this.discover = new DiscoverService();
    }

    public JsonNode dispatch(String method, JsonNode params) {
        return switch (method) {
            case "initialize" -> initialize(params);
            case "ping" -> McpMessages.RPC.createObjectNode();
            case "tools/list" -> toolsList();
            case "tools/call" -> toolsCall(params);
            case "resources/list" -> resourcesList();
            case "resources/read" -> resourcesRead(params);
            default -> throw new McpMessages.RpcException(
                McpMessages.METHOD_NOT_FOUND, "Method not found: " + method);
        };
    }

    JsonNode initialize(JsonNode params) {
        String requested = params == null ? "" : params.path("protocolVersion").asText("");
        McpMessages.InitializeResult result = new McpMessages.InitializeResult(
            McpMessages.negotiateProtocol(requested),
            McpMessages.Capabilities.advertised(),
            new McpMessages.ServerInfo("condense", VersionProvider.applicationVersion())
        );
        return McpMessages.RPC.valueToTree(result);
    }

    JsonNode toolsList() {
        List<McpMessages.ToolSpec> tools = List.of(
            new McpMessages.ToolSpec(
                "run",
                "Proxy a command through Condense and return the schema-1 diagnostics document.",
                runSchema()),
            new McpMessages.ToolSpec(
                "explain",
                "Explain which filter stages dropped or rewrote command output.",
                explainSchema()),
            new McpMessages.ToolSpec(
                "read",
                "Read a workspace file with comment-strip or outline.",
                readSchema()),
            new McpMessages.ToolSpec(
                "discover",
                "Recommend filter definitions from repository manifests and lockfiles.",
                discoverSchema())
        );
        return McpMessages.RPC.valueToTree(new McpMessages.ToolsListResult(tools));
    }

    JsonNode resourcesList() {
        List<McpMessages.ResourceSpec> resources = List.of(
            new McpMessages.ResourceSpec(
                McpMessages.GAIN_URI,
                "gain",
                "application/json",
                "Token-savings summary, same JSON as condense gain --format json."),
            new McpMessages.ResourceSpec(
                McpMessages.DOCTOR_URI,
                "doctor",
                "application/json",
                "Persistence and hook diagnosis, same JSON as condense doctor --format json.")
        );
        return McpMessages.RPC.valueToTree(new McpMessages.ResourcesListResult(resources));
    }

    JsonNode toolsCall(JsonNode params) {
        if (params == null || !params.hasNonNull("name")) {
            throw new McpMessages.RpcException(McpMessages.INVALID_PARAMS, "tools/call requires name");
        }
        String name = params.get("name").asText();
        JsonNode args = params.path("arguments");
        if (args.isMissingNode() || args.isNull()) {
            args = McpMessages.RPC.createObjectNode();
        }
        return switch (name) {
            case "run" -> callRun(args);
            case "explain" -> callExplain(args);
            case "read" -> callRead(args);
            case "discover" -> callDiscover(args);
            default -> value(McpMessages.ToolResult.error("Unknown tool: " + name));
        };
    }

    JsonNode resourcesRead(JsonNode params) {
        if (params == null || !params.hasNonNull("uri")) {
            throw new McpMessages.RpcException(McpMessages.INVALID_PARAMS, "resources/read requires uri");
        }
        String uri = params.get("uri").asText();
        return switch (uri) {
            case McpMessages.GAIN_URI -> resourceJson(uri, compact(gain.buildReport("global", 30, 10)));
            case McpMessages.DOCTOR_URI -> resourceJson(uri, compact(doctor.diagnose()));
            default -> throw new McpMessages.RpcException(
                McpMessages.INVALID_PARAMS, "Unknown resource: " + uri);
        };
    }

    private JsonNode callRun(JsonNode args) {
        List<String> command = argv(args.get("command"));
        if (command == null) {
            return value(McpMessages.ToolResult.error(
                "run.command must be a non-empty argv array, not a shell string"));
        }
        boolean ultra = flag(args, "ultra_compact");
        try {
            ProxyService.Outcome outcome = proxy.run(command, 0, ultra, true, null, System.err);
            Document document = outcome.filtered().document();
            return value(McpMessages.ToolResult.ok(JsonRenderer.render(document)));
        } catch (IllegalArgumentException e) {
            return value(McpMessages.ToolResult.error(e.getMessage()));
        } catch (Exception e) {
            return value(McpMessages.ToolResult.error(
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private JsonNode callExplain(JsonNode args) {
        List<String> command = argv(args.get("command"));
        if (command == null) {
            return value(McpMessages.ToolResult.error(
                "explain.command must be a non-empty argv array, not a shell string"));
        }
        boolean ultra = flag(args, "ultra_compact");
        int exitCode = args.path("exit_code").isNumber() ? args.get("exit_code").asInt() : 0;
        String input = text(args, "input");
        try {
            var report = input == null
                ? explain.explainExecuted(command, 0, ultra, ExplainService.DEFAULT_DROPPED_LIMIT)
                : explainGated(command, input, exitCode, ultra);
            return value(McpMessages.ToolResult.ok(compact(report)));
        } catch (IllegalArgumentException e) {
            return value(McpMessages.ToolResult.error(e.getMessage()));
        } catch (Exception e) {
            return value(McpMessages.ToolResult.error(
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private com.condense.explain.ExplainReport explainGated(
            List<String> command,
            String input,
            int exitCode,
            boolean ultra
    ) {
        ReadPathGate.GateResult gate = ReadPathGate.openFile(
            Path.of(input),
            cwd(),
            null,
            ExplainService.MAX_INPUT_BYTES
        );
        if (!gate.ok()) {
            throw new IllegalArgumentException(gate.error());
        }
        return explain.explainStdin(
            command,
            gate.bytes(),
            exitCode,
            0,
            ultra,
            ExplainService.DEFAULT_DROPPED_LIMIT,
            cwd()
        );
    }

    private JsonNode callRead(JsonNode args) {
        String path = text(args, "path");
        if (path == null) {
            return value(McpMessages.ToolResult.error("read.path is required"));
        }
        boolean ultra = flag(args, "ultra_compact");
        ReadLevel level;
        try {
            level = ultra ? ReadLevel.OUTLINE : ReadLevel.parse(text(args, "level"));
        } catch (IllegalArgumentException e) {
            return value(McpMessages.ToolResult.error(e.getMessage()));
        }
        long started = System.nanoTime();
        ReadService.Request request = new ReadService.Request(
            Path.of(path),
            null,
            false,
            level,
            null,
            cwd(),
            null,
            null
        );
        ReadService.Outcome outcome = read.execute(request);
        if (!outcome.ok()) {
            return value(McpMessages.ToolResult.error(
                outcome.stderr() == null || outcome.stderr().isBlank()
                    ? "read failed"
                    : outcome.stderr()));
        }
        recordRead(path, level, outcome, started);
        return value(McpMessages.ToolResult.ok(compact(outcome.report())));
    }

    private JsonNode callDiscover(JsonNode args) {
        String rootText = text(args, "root");
        Path root = rootText == null ? null : Path.of(rootText);
        DiscoverReport report = discover.discover(cwd(), root);
        if (report.failed()) {
            return value(McpMessages.ToolResult.error(report.error()));
        }
        return value(McpMessages.ToolResult.ok(compact(report)));
    }

    private void recordRead(String path, ReadLevel level, ReadService.Outcome outcome, long startedNanos) {
        if (tracking == null || outcome == null || !outcome.ok()) {
            return;
        }
        try {
            tracking.insert(
                "read --level " + level.token() + " " + path,
                ProjectFingerprint.ofCurrentDir(),
                System.getProperty("user.dir"),
                outcome.rawTokens(),
                outcome.outTokens(),
                Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L)
            );
        } catch (Exception ignored) {
            // fail-open — the tool result is already built
        }
    }

    private static JsonNode resourceJson(String uri, String compactJson) {
        return value(new McpMessages.ResourceReadResult(List.of(
            new McpMessages.ResourceContents(uri, "application/json", compactJson))));
    }

    private static List<String> argv(JsonNode command) {
        if (command == null || command.isNull() || command.isMissingNode()) {
            return null;
        }
        if (command.isTextual()) {
            return null;
        }
        if (!command.isArray() || command.isEmpty()) {
            return null;
        }
        List<String> tokens = new ArrayList<>();
        for (JsonNode item : command) {
            if (!item.isTextual() || item.asText().isBlank()) {
                return null;
            }
            tokens.add(item.asText());
        }
        return List.copyOf(tokens);
    }

    private static boolean flag(JsonNode args, String snake) {
        if (args == null) {
            return false;
        }
        if (args.has(snake) && args.get(snake).asBoolean(false)) {
            return true;
        }
        String camel = snake.contains("_")
            ? snake.replace("ultra_compact", "ultraCompact")
            : snake;
        return args.has(camel) && args.get(camel).asBoolean(false);
    }

    private static String text(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) {
            return null;
        }
        String value = args.get(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Path cwd() {
        return Path.of(System.getProperty("user.dir", "."));
    }

    private static String compact(Object value) {
        try {
            return Mappers.JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize MCP payload", e);
        }
    }

    private static JsonNode value(Object record) {
        return McpMessages.RPC.valueToTree(record);
    }

    private static JsonNode runSchema() {
        ObjectNode schema = McpMessages.RPC.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        objectArray(props, "command", "Argv tokens only. Never a single shell string.");
        props.putObject("ultra_compact").put("type", "boolean");
        required(schema, "command");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static JsonNode explainSchema() {
        ObjectNode schema = McpMessages.RPC.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        objectArray(props, "command", "Argv tokens only. Never a single shell string.");
        props.putObject("input").put("type", "string")
            .put("description", "Workspace-contained captured stdout file.");
        props.putObject("exit_code").put("type", "integer");
        props.putObject("ultra_compact").put("type", "boolean");
        required(schema, "command");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static JsonNode discoverSchema() {
        ObjectNode schema = McpMessages.RPC.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("root").put("type", "string")
            .put("description", "Optional workspace root. May only narrow, not widen.");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static JsonNode readSchema() {
        ObjectNode schema = McpMessages.RPC.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("path").put("type", "string")
            .put("description", "File path contained by the workspace root.");
        props.putObject("level").put("type", "string")
            .put("description", "verbatim, comments, or outline.");
        props.putObject("ultra_compact").put("type", "boolean");
        required(schema, "path");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static void objectArray(ObjectNode props, String name, String description) {
        ObjectNode command = props.putObject(name);
        command.put("type", "array");
        command.put("description", description);
        command.putObject("items").put("type", "string");
    }

    private static void required(ObjectNode schema, String name) {
        ArrayNode required = schema.putArray("required");
        required.add(name);
    }
}
