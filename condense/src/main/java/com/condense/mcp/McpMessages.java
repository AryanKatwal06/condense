package com.condense.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Set;

/**
 * JSON-RPC 2.0 / MCP wire records. Serialized with {@link #RPC} (camelCase),
 * never {@link com.condense.core.Mappers#JSON} — that mapper is snake_case
 * for Condense documents.
 */
public final class McpMessages {

    public static final ObjectMapper RPC = new ObjectMapper();

    public static final String JSONRPC = "2.0";
    public static final String FALLBACK_PROTOCOL = "2024-11-05";
    public static final Set<String> PROTOCOL_VERSIONS = Set.of(
        "2024-11-05", "2025-03-26", "2025-06-18");

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    public static final String GAIN_URI = "condense://gain";
    public static final String DOCTOR_URI = "condense://doctor";

    private McpMessages() {}

    public static String negotiateProtocol(String requested) {
        return requested != null && PROTOCOL_VERSIONS.contains(requested)
            ? requested
            : FALLBACK_PROTOCOL;
    }

    @RegisterForReflection
    public record JsonRpcError(int code, String message, JsonNode data) {
        public JsonRpcError(int code, String message) {
            this(code, message, null);
        }
    }

    @RegisterForReflection
    public record ServerInfo(String name, String version) {}

    @RegisterForReflection
    public record EmptyCapability() {}

    @RegisterForReflection
    public record Capabilities(EmptyCapability tools, EmptyCapability resources) {
        public static Capabilities advertised() {
            return new Capabilities(new EmptyCapability(), new EmptyCapability());
        }
    }

    @RegisterForReflection
    public record InitializeResult(String protocolVersion, Capabilities capabilities, ServerInfo serverInfo) {}

    @RegisterForReflection
    public record ToolSpec(String name, String description, JsonNode inputSchema) {}

    @RegisterForReflection
    public record ToolsListResult(List<ToolSpec> tools) {}

    @RegisterForReflection
    public record ResourceSpec(String uri, String name, String mimeType, String description) {}

    @RegisterForReflection
    public record ResourcesListResult(List<ResourceSpec> resources) {}

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolContent(String type, String text) {
        public static ToolContent text(String text) {
            return new ToolContent("text", text);
        }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolResult(List<ToolContent> content, Boolean isError) {
        public static ToolResult ok(String compactJson) {
            return new ToolResult(List.of(ToolContent.text(compactJson)), null);
        }

        public static ToolResult error(String message) {
            return new ToolResult(List.of(ToolContent.text(message)), true);
        }
    }

    @RegisterForReflection
    public record ResourceContents(String uri, String mimeType, String text) {}

    @RegisterForReflection
    public record ResourceReadResult(List<ResourceContents> contents) {}

    public static final class RpcException extends RuntimeException {
        private final int code;

        public RpcException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }
}
