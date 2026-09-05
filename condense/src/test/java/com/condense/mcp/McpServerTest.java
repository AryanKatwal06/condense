package com.condense.mcp;

import com.condense.VersionProvider;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerTest {

    private final McpServer server = new McpServer(new McpHandlers());

    @Test
    void initializeEchoesKnownProtocolAndAdvertisesToolsAndResources() throws Exception {
        String response = server.handleLine("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}
            """.trim());
        JsonNode root = McpMessages.RPC.readTree(response);
        assertThat(root.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(root.get("id").asInt()).isEqualTo(1);
        JsonNode result = root.get("result");
        assertThat(result.get("protocolVersion").asText()).isEqualTo("2025-03-26");
        assertThat(result.get("capabilities").has("tools")).isTrue();
        assertThat(result.get("capabilities").has("resources")).isTrue();
        assertThat(result.get("capabilities").has("prompts")).isFalse();
        assertThat(result.get("serverInfo").get("name").asText()).isEqualTo("condense");
        assertThat(result.get("serverInfo").get("version").asText())
            .isEqualTo(VersionProvider.applicationVersion());
        assertThat(response).doesNotContain("\n");
    }

    @Test
    void unknownProtocolFallsBackTo2024() throws Exception {
        String response = server.handleLine("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}
            """.trim());
        JsonNode root = McpMessages.RPC.readTree(response);
        assertThat(root.get("result").get("protocolVersion").asText()).isEqualTo("2024-11-05");
    }

    @Test
    void initializedNotificationHasNoResponse() {
        assertThat(server.handleLine("""
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            """.trim())).isNull();
    }

    @Test
    void missingIdOnRequestIsJsonRpcMinus32600() throws Exception {
        String response = server.handleLine("""
            {"jsonrpc":"2.0","method":"ping"}
            """.trim());
        JsonNode root = McpMessages.RPC.readTree(response);
        assertThat(root.get("error").get("code").asInt()).isEqualTo(-32600);
        assertThat(root.get("id").isNull()).isTrue();
    }

    @Test
    void nullIdOnRequestIsJsonRpcMinus32600() throws Exception {
        String response = server.handleLine("""
            {"jsonrpc":"2.0","id":null,"method":"tools/list"}
            """.trim());
        JsonNode root = McpMessages.RPC.readTree(response);
        assertThat(root.get("error").get("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void pingReturnsEmptyObject() throws Exception {
        String response = server.handleLine("""
            {"jsonrpc":"2.0","id":"ping-1","method":"ping"}
            """.trim());
        JsonNode root = McpMessages.RPC.readTree(response);
        assertThat(root.get("id").asText()).isEqualTo("ping-1");
        assertThat(root.get("result").isObject()).isTrue();
        assertThat(root.get("result").size()).isZero();
        assertThat(response).doesNotContain("\n");
    }

    @Test
    void unknownMethodIsJsonRpcMinus32601() throws Exception {
        String response = server.handleLine("""
            {"jsonrpc":"2.0","id":9,"method":"prompts/list"}
            """.trim());
        JsonNode root = McpMessages.RPC.readTree(response);
        assertThat(root.get("error").get("code").asInt()).isEqualTo(-32601);
        assertThat(root.get("error").get("message").asText()).contains("prompts/list");
    }

    @Test
    void toolsListNamesExactlyRunExplainReadDiscover() throws Exception {
        String response = server.handleLine("""
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}
            """.trim());
        JsonNode tools = McpMessages.RPC.readTree(response).get("result").get("tools");
        List<String> names = new ArrayList<>();
        tools.forEach(node -> names.add(node.get("name").asText()));
        assertThat(names).containsExactly("run", "explain", "read", "discover", "propose");
        assertThat(tools.get(0).get("inputSchema").get("type").asText()).isEqualTo("object");
    }

    @Test
    void resourcesListNamesGainAndDoctor() throws Exception {
        String response = server.handleLine("""
            {"jsonrpc":"2.0","id":3,"method":"resources/list"}
            """.trim());
        JsonNode resources = McpMessages.RPC.readTree(response).get("result").get("resources");
        List<String> uris = new ArrayList<>();
        resources.forEach(node -> uris.add(node.get("uri").asText()));
        assertThat(uris).containsExactly("condense://gain", "condense://doctor");
    }

    @Test
    void scriptedSessionStdoutIsOnlyJsonRpcLines() throws Exception {
        String session = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            {"jsonrpc":"2.0","id":2,"method":"ping"}
            """;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        new McpServer(new McpHandlers()).serve(
            new ByteArrayInputStream(session.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(captured, true, StandardCharsets.UTF_8));
        String stdout = captured.toString(StandardCharsets.UTF_8);
        assertThat(stdout).doesNotContain("condense[filtered]");
        String[] lines = stdout.split("\\R", -1);
        int objects = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = McpMessages.RPC.readTree(line);
            assertThat(node.get("jsonrpc").asText()).isEqualTo("2.0");
            objects++;
        }
        assertThat(objects).isEqualTo(2);
    }
}
