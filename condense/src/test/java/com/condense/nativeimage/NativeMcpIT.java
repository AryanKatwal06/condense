package com.condense.nativeimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native-image proof that {@code condense mcp --start} speaks MCP on stdio.
 * Never skips.
 */
class NativeMcpIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateDirs() throws Exception {
        Files.createDirectories(configDir());
        Files.createDirectories(dataDir());
    }

    @Test
    void helpMentionsMcpAndStart() throws Exception {
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), "mcp", "--help");
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isZero();
        assertThat(result.stdout()).containsIgnoringCase("MCP");
        assertThat(result.stdout()).contains("--start");
    }

    @Test
    void stubbedPytestRunReturnsSchemaOneTestDocument() throws Exception {
        Path stubDir = tempDir.resolve("bin");
        Files.createDirectories(stubDir);
        Files.write(stubDir.resolve("fixture.txt"), loadClasspathFixture("/fixtures/pytest/typical.txt"));
        writePytestStub(stubDir);

        NativeBinarySupport.StartedRun session = NativeBinarySupport.start(
            configDir(), dataDir(), stubDir, tempDir, null, "mcp", "--start");
        session.writeStdin("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"native-it","version":"0"}}}
            """);
        waitForStdout(session);
        assertThat(session.isAlive()).as("server must stay up until stdin closes").isTrue();
        session.writeStdin("""
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run","arguments":{"command":["pytest"]}}}
            """);
        session.closeStdin();
        NativeBinarySupport.CliResult result = session.await();

        assertThat(result.stdout()).doesNotStartWith("condense[filtered]");
        List<JsonNode> messages = jsonRpcLines(result.stdout());
        assertThat(messages).hasSizeGreaterThanOrEqualTo(2);
        JsonNode init = messages.get(0);
        assertThat(init.get("result").get("protocolVersion").asText()).isIn(
            "2024-11-05", "2025-03-26", "2025-06-18");
        JsonNode tool = messages.get(1).get("result");
        assertThat(tool.path("isError").asBoolean(false)).isFalse();
        JsonNode document = JSON.readTree(tool.get("content").get(0).get("text").asText());
        assertThat(document.get("schema_version").asInt()).isEqualTo(1);
        assertThat(document.get("kind").asText()).isEqualTo("test");
        assertThat(document.get("child_exit_code").asInt()).isEqualTo(1);
    }

    @Test
    void readInsideWorkdirSucceedsAndOutsideIsError() throws Exception {
        Path inside = tempDir.resolve("Src.java");
        Files.writeString(inside, "class Src {}\n");
        Path outside = Path.of(System.getProperty("java.io.tmpdir"))
            .resolve("condense-native-mcp-escape-" + tempDir.getFileName() + ".java");
        Files.writeString(outside, "class SecretLeak {}\n");
        try {
            NativeBinarySupport.StartedRun session = NativeBinarySupport.start(
                configDir(), dataDir(), null, tempDir, null, "mcp", "--start");
            String insideJson = JSON.writeValueAsString(inside.toString());
            String outsideJson = JSON.writeValueAsString(outside.toString());
            session.writeStdin("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read","arguments":{"path":%s}}}
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read","arguments":{"path":%s}}}
                """.formatted(insideJson, outsideJson));
            session.closeStdin();
            NativeBinarySupport.CliResult result = session.await();
            List<JsonNode> messages = jsonRpcLines(result.stdout());
            assertThat(messages).hasSizeGreaterThanOrEqualTo(2);
            JsonNode ok = messages.get(0).get("result");
            assertThat(ok.path("isError").asBoolean(false)).isFalse();
            assertThat(ok.get("content").get(0).get("text").asText()).contains("Src");
            JsonNode denied = messages.get(1).get("result");
            assertThat(denied.get("isError").asBoolean()).isTrue();
            assertThat(denied.get("content").get(0).get("text").asText()).doesNotContain("SecretLeak");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void toolsListIncludesDiscover() throws Exception {
        NativeBinarySupport.StartedRun session = NativeBinarySupport.start(
            configDir(), dataDir(), null, tempDir, null, "mcp", "--start");
        session.writeStdin("""
            {"jsonrpc":"2.0","id":1,"method":"tools/list"}
            """);
        session.closeStdin();
        NativeBinarySupport.CliResult result = session.await();
        List<JsonNode> messages = jsonRpcLines(result.stdout());
        assertThat(messages).isNotEmpty();
        assertThat(messages.get(0).get("result").toString()).contains("\"name\":\"discover\"");
        assertThat(messages.get(0).get("result").toString()).contains("\"name\":\"propose\"");
    }

    @Test
    void gainResourceHasTotalCommands() throws Exception {
        NativeBinarySupport.StartedRun session = NativeBinarySupport.start(
            configDir(), dataDir(), null, tempDir, null, "mcp", "--start");
        session.writeStdin("""
            {"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"condense://gain"}}
            """);
        session.closeStdin();
        NativeBinarySupport.CliResult result = session.await();
        List<JsonNode> messages = jsonRpcLines(result.stdout());
        assertThat(messages).isNotEmpty();
        String text = messages.get(0).get("result").get("contents").get(0).get("text").asText();
        JsonNode report = JSON.readTree(text);
        assertThat(report.has("total_commands")).isTrue();
        assertThat(report.get("total_commands").asInt()).isGreaterThanOrEqualTo(0);
    }

    private static void waitForStdout(NativeBinarySupport.StartedRun session) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (session.stdoutSoFar().isBlank() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(session.stdoutSoFar())
            .as("initialize must produce a JSON-RPC line")
            .isNotBlank();
    }

    private static List<JsonNode> jsonRpcLines(String stdout) throws Exception {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : stdout.split("\\R", -1)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = JSON.readTree(line);
            assertThat(node.path("jsonrpc").asText()).isEqualTo("2.0");
            nodes.add(node);
        }
        return nodes;
    }

    private static void writePytestStub(Path stubDir) throws Exception {
        if (NativeBinarySupport.isWindows()) {
            Files.writeString(stubDir.resolve("pytest.cmd"),
                "@echo off\r\ntype \"%~dp0fixture.txt\"\r\nexit /b 1\r\n",
                StandardCharsets.UTF_8);
            return;
        }
        Path script = stubDir.resolve("pytest");
        Files.writeString(script, "#!/bin/sh\ncat \"$(dirname \"$0\")/fixture.txt\"\nexit 1\n",
            StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            script.toFile().setExecutable(true);
        }
    }

    private static byte[] loadClasspathFixture(String resource) throws Exception {
        try (var in = NativeMcpIT.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource + " must be on the test classpath").isNotNull();
            return in.readAllBytes();
        }
    }

    private Path configDir() {
        return tempDir.resolve("config");
    }

    private Path dataDir() {
        return tempDir.resolve("data");
    }
}
