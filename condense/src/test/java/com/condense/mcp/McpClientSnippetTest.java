package com.condense.mcp;

import com.condense.commands.McpCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientSnippetTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void everyClientSnippetIsValidJson() throws Exception {
        for (McpClient client : McpClient.values()) {
            JsonNode root = JSON.readTree(client.snippet());
            assertThat(root.isObject())
                .as("Client %s snippet must parse as JSON object", client.id())
                .isTrue();
            assertThat(client.snippet())
                .as("Client %s snippet must reference condense", client.id())
                .contains("condense");
        }
    }

    @Test
    void zedHasContextServersFormat() throws Exception {
        JsonNode root = JSON.readTree(McpClient.ZED.snippet());
        assertThat(root.has("context_servers")).isTrue();
        JsonNode condense = root.path("context_servers").path("condense");
        assertThat(condense.path("command").path("path").asText()).isEqualTo("condense");
        assertThat(condense.path("command").path("args").get(1).asText()).isEqualTo("--start");
    }

    @Test
    void vscodeHasServersArrayFormat() throws Exception {
        JsonNode root = JSON.readTree(McpClient.VSCODE.snippet());
        assertThat(root.has("servers")).isTrue();
        assertThat(root.get("servers").isArray()).isTrue();
        JsonNode server = root.get("servers").get(0);
        assertThat(server.get("name").asText()).isEqualTo("condense");
        assertThat(server.get("command").asText()).isEqualTo("condense");
    }

    @Test
    void opencodeHasMcpServersFormat() throws Exception {
        JsonNode root = JSON.readTree(McpClient.OPENCODE.snippet());
        assertThat(root.has("mcp")).isTrue();
        assertThat(root.path("mcp").path("servers").has("condense")).isTrue();
    }

    @Test
    void claudeCodeHasCliSetupCommand() {
        assertThat(McpClient.CLAUDE_CODE.setupCommand())
            .isPresent()
            .contains("claude mcp add condense -- condense mcp --start");
    }

    @Test
    void pathResolutionWithHome(@TempDir Path fakeHome) {
        Path claudeConfig = McpClient.CLAUDE_CODE.resolveConfigPath(fakeHome);
        assertThat(claudeConfig).isEqualTo(fakeHome.resolve(".claude.json"));

        Path cursorConfig = McpClient.CURSOR.resolveConfigPath(fakeHome);
        assertThat(cursorConfig).isEqualTo(fakeHome.resolve(".cursor/mcp.json"));

        Path windsurfConfig = McpClient.WINDSURF.resolveConfigPath(fakeHome);
        assertThat(windsurfConfig).isEqualTo(fakeHome.resolve(".codeium/windsurf/mcp_config.json"));

        Path antigravityConfig = McpClient.ANTIGRAVITY.resolveConfigPath(fakeHome);
        assertThat(antigravityConfig).isEqualTo(fakeHome.resolve(".gemini/antigravity-cli/mcp_config.json"));
    }

    @Test
    void aliasParsingResolvesExpectedClients() {
        assertThat(McpClient.parse("copilot")).contains(McpClient.VSCODE);
        assertThat(McpClient.parse("gemini")).contains(McpClient.ANTIGRAVITY);
        assertThat(McpClient.parse("claude_desktop")).contains(McpClient.CLAUDE_DESKTOP);
        assertThat(McpClient.parse("zed")).contains(McpClient.ZED);
        assertThat(McpClient.parse("all")).isEmpty();
        assertThat(McpClient.parse("nonexistent_client")).isEmpty();
    }

    @Test
    void cliCommandPrintsClientSnippet(@TempDir Path fakeHome) {
        System.setProperty("condense.test.home", fakeHome.toAbsolutePath().toString());
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream orig = System.out;
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            try {
                McpCommand cmd = new McpCommand();
                cmd.client = "zed";
                int exit = cmd.call();
                assertThat(exit).isZero();
                String output = out.toString(StandardCharsets.UTF_8);
                assertThat(output).contains("Zed");
                assertThat(output).contains("context_servers");
            } finally {
                System.setOut(orig);
            }
        } finally {
            System.clearProperty("condense.test.home");
        }
    }

    @Test
    void cliCommandListsClients() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream orig = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            McpCommand cmd = new McpCommand();
            cmd.listClients = true;
            int exit = cmd.call();
            assertThat(exit).isZero();
            String output = out.toString(StandardCharsets.UTF_8);
            assertThat(output).contains("Supported MCP Clients");
            assertThat(output).contains("claude-desktop");
            assertThat(output).contains("zed");
            assertThat(output).contains("antigravity");
        } finally {
            System.setOut(orig);
        }
    }

    @Test
    void cliCommandRejectsUnknownClient() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            McpCommand cmd = new McpCommand();
            cmd.client = "unknown-agent-xyz";
            int exit = cmd.call();
            assertThat(exit).isEqualTo(1);
            String errorOutput = err.toString(StandardCharsets.UTF_8);
            assertThat(errorOutput).contains("unknown client 'unknown-agent-xyz'");
        } finally {
            System.setErr(origErr);
        }
    }
}
