package com.condense.mcp;

import com.condense.core.IsolatedPlatformDirs;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExpandToolTest {

    @TempDir
    Path tempDir;

    private McpHandlers handlers;
    private Path teeDir;

    @BeforeEach
    void setUp() throws Exception {
        IsolatedPlatformDirs dirs = new IsolatedPlatformDirs(
            tempDir.resolve("config"), tempDir.resolve("data"));
        teeDir = dirs.getDataDir().resolve("tee");
        Files.createDirectories(teeDir);
        handlers = new McpHandlers(null, null, null, null, null, null, dirs);
    }

    @Test
    void toolsListIncludesExpand() {
        JsonNode result = handlers.dispatch("tools/list", null);
        assertThat(result.toString()).contains("\"name\":\"expand\"");
    }

    @Test
    void expandReturnsRequestedOneBasedRange() throws Exception {
        Path tee = writeTee("range.txt", 10);

        JsonNode result = callExpand(tee, 3, 5);

        assertThat(result.path("isError").asBoolean(false)).isFalse();
        JsonNode report = report(result);
        assertThat(report.get("content").asText()).isEqualTo("line-3\nline-4\nline-5\n");
        assertThat(report.get("start_line").asInt()).isEqualTo(3);
        assertThat(report.get("end_line").asInt()).isEqualTo(5);
        assertThat(report.get("lines_returned").asInt()).isEqualTo(3);
        assertThat(report.get("total_lines").asInt()).isEqualTo(10);
        assertThat(report.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void expandClampsEndToFileLength() throws Exception {
        Path tee = writeTee("short.txt", 4);

        JsonNode report = report(callExpand(tee, 3, 50));

        assertThat(report.get("end_line").asInt()).isEqualTo(4);
        assertThat(report.get("lines_returned").asInt()).isEqualTo(2);
        assertThat(report.get("total_lines").asInt()).isEqualTo(4);
    }

    @Test
    void expandRejectsFileOutsideTeeDirectoryAndLeaksNothing() throws Exception {
        Path outside = tempDir.resolve("secret.txt");
        Files.writeString(outside, "SECRET_PAYLOAD_SHOULD_NOT_LEAK");

        JsonNode result = callExpand(outside, 1, 1);

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.get("content").get(0).get("text").asText())
            .doesNotContain("SECRET_PAYLOAD_SHOULD_NOT_LEAK");
    }

    @Test
    void expandRejectsMissingTeeFile() {
        JsonNode result = callExpand(teeDir.resolve("does-not-exist.txt"), 1, 1);
        assertThat(result.path("isError").asBoolean()).isTrue();
    }

    @Test
    void expandRejectsNonPositiveStartLine() throws Exception {
        Path tee = writeTee("bad.txt", 4);
        JsonNode result = callExpand(tee, 0, 2);
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.get("content").get(0).get("text").asText()).contains(">= 1");
    }

    @Test
    void expandRejectsReversedRange() throws Exception {
        Path tee = writeTee("bad.txt", 4);
        JsonNode result = callExpand(tee, 3, 1);
        assertThat(result.path("isError").asBoolean()).isTrue();
    }

    @Test
    void expandRejectsOversizedWindow() throws Exception {
        Path tee = writeTee("big.txt", 4);
        JsonNode result = callExpand(tee, 1, 600);
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.get("content").get(0).get("text").asText()).contains("500");
    }

    @Test
    void expandRequiresAllArguments() {
        JsonNode result = handlers.dispatch("tools/call",
            params("expand", """
                {"tee_file":"%s"}""".formatted(escape(teeDir.resolve("x.txt")))));
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.get("content").get(0).get("text").asText()).contains("required");
    }

    private Path writeTee(String name, int lines) throws Exception {
        Path tee = teeDir.resolve(name);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            sb.append("line-").append(i).append('\n');
        }
        Files.writeString(tee, sb.toString());
        return tee;
    }

    private JsonNode callExpand(Path tee, int start, int end) {
        String json = """
            {"tee_file":"%s","start_line":%d,"end_line":%d}"""
            .formatted(escape(tee), start, end);
        return handlers.dispatch("tools/call", params("expand", json));
    }

    private static JsonNode params(String name, String argumentsJson) {
        try {
            return McpMessages.RPC.readTree("""
                {"name":"%s","arguments":%s}""".formatted(name, argumentsJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode report(JsonNode toolResult) {
        try {
            return McpMessages.RPC.readTree(
                toolResult.get("content").get(0).get("text").asText());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String escape(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\");
    }
}
