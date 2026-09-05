package com.condense.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Newline-delimited JSON-RPC 2.0 loop. One object per line; no embedded newlines.
 */
public final class McpServer {

    private static final Logger log = Logger.getLogger(McpServer.class);

    private final McpHandlers handlers;

    public McpServer(McpHandlers handlers) {
        this.handlers = handlers;
    }

    public void serve(InputStream in, PrintStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String response = handleLine(line);
            if (response != null) {
                out.print(response);
                if (!response.endsWith("\n")) {
                    out.print('\n');
                }
                out.flush();
            }
        }
    }

    /**
     * Handle one incoming line. Returns a compact JSON-RPC object, or {@code null}
     * for notifications (including {@code notifications/initialized}).
     */
    public String handleLine(String line) {
        JsonNode root;
        try {
            root = McpMessages.RPC.readTree(line);
        } catch (Exception e) {
            return writeError(null, McpMessages.PARSE_ERROR, "Parse error");
        }
        if (root == null || !root.isObject()) {
            return writeError(null, McpMessages.INVALID_REQUEST, "Invalid Request");
        }
        String version = root.path("jsonrpc").asText("");
        if (!McpMessages.JSONRPC.equals(version)) {
            return writeError(root.get("id"), McpMessages.INVALID_REQUEST, "Invalid Request");
        }
        String method = root.path("method").asText("");
        if (method.isBlank()) {
            return writeError(root.get("id"), McpMessages.INVALID_REQUEST, "Invalid Request");
        }
        JsonNode id = root.get("id");
        boolean missingId = id == null || id.isNull();
        if (method.startsWith("notifications/")) {
            return null;
        }
        if (missingId) {
            return writeError(null, McpMessages.INVALID_REQUEST, "Invalid Request");
        }
        try {
            JsonNode result = handlers.dispatch(method, root.get("params"));
            return writeResult(id, result);
        } catch (McpMessages.RpcException e) {
            return writeError(id, e.code(), e.getMessage());
        } catch (Exception e) {
            log.warnf("MCP handler failed for %s: %s", method, e.getMessage());
            return writeError(id, McpMessages.INTERNAL_ERROR, "Internal error");
        }
    }

    private static String writeResult(JsonNode id, JsonNode result) {
        ObjectNode resp = McpMessages.RPC.createObjectNode();
        resp.put("jsonrpc", McpMessages.JSONRPC);
        resp.set("id", id);
        resp.set("result", result == null ? McpMessages.RPC.createObjectNode() : result);
        return compact(resp);
    }

    private static String writeError(JsonNode id, int code, String message) {
        ObjectNode resp = McpMessages.RPC.createObjectNode();
        resp.put("jsonrpc", McpMessages.JSONRPC);
        if (id == null || id.isMissingNode()) {
            resp.putNull("id");
        } else {
            resp.set("id", id);
        }
        ObjectNode error = resp.putObject("error");
        error.put("code", code);
        error.put("message", message == null ? "Error" : message);
        return compact(resp);
    }

    private static String compact(JsonNode node) {
        try {
            return McpMessages.RPC.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
        }
    }
}
