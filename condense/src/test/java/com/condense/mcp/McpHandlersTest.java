package com.condense.mcp;

import com.condense.analytics.GainRepository;
import com.condense.core.CommandExecutor;
import com.condense.core.ConfigLoader;
import com.condense.core.ExecutionResult;
import com.condense.core.FilterStrategy;
import com.condense.core.IsolatedPlatformDirs;
import com.condense.core.PlatformDirs;
import com.condense.core.ProxyService;
import com.condense.core.StreamListener;
import com.condense.core.StrategyRegistry;
import com.condense.core.TeeWriter;
import com.condense.core.TrackingRepository;
import com.condense.corpus.CorpusRunner;
import com.condense.doctor.DoctorService;
import com.condense.explain.ExplainService;
import com.condense.filter.git.GitStatusFilter;
import com.condense.filter.pipeline.config.FilterOverrideLoader;
import com.condense.filter.python.PytestFilter;
import com.condense.hooks.HookInstaller;
import com.condense.hooks.HookTool;
import com.condense.ir.Document;
import com.condense.ir.JsonRenderer;
import com.condense.read.ReadService;
import com.condense.trust.TrustGate;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.inject.Vetoed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class McpHandlersTest {

    @TempDir
    Path tempDir;

    private TrackingRepository tracking;
    private McpServer server;

    @BeforeEach
    void setUp() throws Exception {
        PlatformDirs dirs = new IsolatedPlatformDirs(tempDir.resolve("config"), tempDir.resolve("data"));
        tracking = new TrackingRepository(dirs);
        ConfigLoader configLoader = new ConfigLoader(dirs);
        TeeWriter teeWriter = new TeeWriter(dirs, configLoader);
        FeedingExecutor executor = new FeedingExecutor();
        FixedRegistry registry = new FixedRegistry();
        ProxyService proxy = new ProxyService(executor, registry, tracking, teeWriter, configLoader);
        ExplainService explain = new ExplainService(registry, executor, configLoader);
        DoctorService doctor = new DoctorService(
            dirs, tracking, new TrustGate(dirs), new FilterOverrideLoader(dirs), noHooks());
        McpHandlers handlers = new McpHandlers(
            proxy, explain, new ReadService(), new GainRepository(tracking), doctor, tracking);
        server = new McpServer(handlers);
    }

    @AfterEach
    void tearDown() {
        if (tracking != null) {
            tracking.close();
        }
    }

    @Test
    void runPytestTypicalIsSchemaOneTestDocument() throws Exception {
        JsonNode result = callTool("run", """
            {"command":["pytest"]}
            """);
        assertThat(result.path("isError").asBoolean(false)).isFalse();
        Document document = JsonRenderer.parse(result.get("content").get(0).get("text").asText());
        assertThat(document.schemaVersion()).isEqualTo(1);
        assertThat(document.kind()).isEqualTo(Document.DocumentKind.TEST);
        assertThat(document.childExitCode()).isEqualTo(1);
        assertThat(result.get("content").get(0).get("text").asText()).contains("test_mul");
    }

    @Test
    void runGitStatusTypicalIsOpaque() throws Exception {
        JsonNode result = callTool("run", """
            {"command":["git","status"]}
            """);
        assertThat(result.path("isError").asBoolean(false)).isFalse();
        Document document = JsonRenderer.parse(result.get("content").get(0).get("text").asText());
        assertThat(document.kind()).isEqualTo(Document.DocumentKind.OPAQUE);
    }

    @Test
    void runChildExitOneIsNotToolError() throws Exception {
        JsonNode result = callTool("run", """
            {"command":["pytest"]}
            """);
        assertThat(result.has("isError")).isFalse();
        Document document = JsonRenderer.parse(result.get("content").get(0).get("text").asText());
        assertThat(document.childExitCode()).isEqualTo(1);
    }

    @Test
    void runRejectsShellString() throws Exception {
        JsonNode result = callTool("run", """
            {"command":"pytest && rm -rf /"}
            """);
        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.get("content").get(0).get("text").asText()).contains("argv");
    }

    @Test
    void explainPytestInputHasTestDocument() throws Exception {
        Path fixture = workspaceFile("pytest.txt");
        Files.writeString(fixture, CorpusRunner.loadFixture("fixtures/pytest/typical.txt"));
        String escaped = fixture.toAbsolutePath().toString().replace("\\", "\\\\");
        JsonNode result = callTool("explain", """
            {"command":["pytest"],"input":"%s","exit_code":1}
            """.formatted(escaped));
        assertThat(result.path("isError").asBoolean(false)).isFalse();
        com.fasterxml.jackson.databind.JsonNode report =
            com.condense.core.Mappers.JSON.readTree(result.get("content").get(0).get("text").asText());
        assertThat(report.get("document").get("kind").asText()).isEqualTo("test");
    }

    @Test
    void readContainedFileSucceeds() throws Exception {
        Path file = workspaceFile("Src.java");
        Files.writeString(file, "class Src { int x = 1; }\n");
        String escaped = file.toAbsolutePath().toString().replace("\\", "\\\\");
        JsonNode result = callTool("read", """
            {"path":"%s","level":"comments"}
            """.formatted(escaped));
        assertThat(result.path("isError").asBoolean(false)).isFalse();
        com.fasterxml.jackson.databind.JsonNode report =
            com.condense.core.Mappers.JSON.readTree(result.get("content").get(0).get("text").asText());
        assertThat(report.get("language").asText()).isEqualTo("java");
        assertThat(report.get("output").asText()).contains("class Src");
    }

    @Test
    void readPathOutsideWorkspaceIsErrorAndLeaksNoBytes() throws Exception {
        Path outside = Path.of(System.getProperty("java.io.tmpdir"))
            .resolve("condense-mcp-escape-" + UUID.randomUUID())
            .resolve("secret.txt");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "SECRET_PAYLOAD_SHOULD_NOT_LEAK");
        try {
            String escaped = outside.toAbsolutePath().toString().replace("\\", "\\\\");
            JsonNode result = callTool("read", """
                {"path":"%s"}
                """.formatted(escaped));
            assertThat(result.get("isError").asBoolean()).isTrue();
            assertThat(result.get("content").get(0).get("text").asText())
                .doesNotContain("SECRET_PAYLOAD_SHOULD_NOT_LEAK");
        } finally {
            Files.deleteIfExists(outside);
            Files.deleteIfExists(outside.getParent());
        }
    }

    @Test
    void explainInputOutsideWorkspaceIsError() throws Exception {
        Path outside = Path.of(System.getProperty("java.io.tmpdir"))
            .resolve("condense-mcp-escape-" + UUID.randomUUID())
            .resolve("out.txt");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "FAILED test_secret");
        try {
            String escaped = outside.toAbsolutePath().toString().replace("\\", "\\\\");
            JsonNode result = callTool("explain", """
                {"command":["pytest"],"input":"%s"}
                """.formatted(escaped));
            assertThat(result.get("isError").asBoolean()).isTrue();
            assertThat(result.get("content").get(0).get("text").asText())
                .doesNotContain("test_secret");
        } finally {
            Files.deleteIfExists(outside);
            Files.deleteIfExists(outside.getParent());
        }
    }

    @Test
    void toolsListIncludesDiscover() throws Exception {
        String response = server.handleLine("""
            {"jsonrpc":"2.0","id":7,"method":"tools/list"}
            """.trim());
        JsonNode root = McpMessages.RPC.readTree(response);
        String names = root.get("result").toString();
        assertThat(names).contains("\"name\":\"discover\"");
        assertThat(names).contains("\"name\":\"run\"");
    }

    @Test
    void discoverReturnsSchemaOneRecommendations() throws Exception {
        JsonNode result = callTool("discover", "{}");
        assertThat(result.path("isError").asBoolean(false)).isFalse();
        com.fasterxml.jackson.databind.JsonNode report =
            com.condense.core.Mappers.JSON.readTree(result.get("content").get(0).get("text").asText());
        assertThat(report.get("schema_version").asInt()).isEqualTo(1);
        assertThat(report.has("recommend")).isTrue();
        assertThat(report.has("files_probed")).isTrue();
    }

    @Test
    void discoverWidenRootIsError() throws Exception {
        Path cwd = Path.of(System.getProperty("user.dir", "."));
        Path workspace = com.condense.core.SafePathValidator.resolveWorkspaceRoot(cwd);
        Path above = workspace.getParent();
        assertThat(above).as("workspace must have a parent to widen against").isNotNull();
        String rootJson = com.condense.core.Mappers.JSON.writeValueAsString(above.toString());
        JsonNode result = callTool("discover", "{\"root\":" + rootJson + "}");
        assertThat(result.path("isError").asBoolean(false)).isTrue();
        assertThat(result.get("content").get(0).get("text").asText()).contains("narrow");
    }

    @Test
    void unknownToolIsError() throws Exception {
        JsonNode result = callTool("analyze_project", "{}");
        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.get("content").get(0).get("text").asText()).contains("Unknown tool");
    }

    @Test
    void unknownResourceIsInvalidParams() throws Exception {
        String response = server.handleLine("""
            {"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"condense://nope"}}
            """.trim());
        JsonNode root = McpMessages.RPC.readTree(response);
        assertThat(root.get("error").get("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void gainResourceRoundTripsExistingRecord() throws Exception {
        tracking.insert("pytest", "abc", "/tmp", 10, 2, 1L);
        String response = server.handleLine("""
            {"jsonrpc":"2.0","id":5,"method":"resources/read","params":{"uri":"condense://gain"}}
            """.trim());
        JsonNode root = McpMessages.RPC.readTree(response);
        String text = root.get("result").get("contents").get(0).get("text").asText();
        com.fasterxml.jackson.databind.JsonNode report = com.condense.core.Mappers.JSON.readTree(text);
        assertThat(report.get("total_commands").asInt()).isEqualTo(1);
        assertThat(report.has("estimator")).isTrue();
    }

    @Test
    void doctorResourceRoundTripsExistingRecord() throws Exception {
        String response = server.handleLine("""
            {"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"condense://doctor"}}
            """.trim());
        JsonNode root = McpMessages.RPC.readTree(response);
        String text = root.get("result").get("contents").get(0).get("text").asText();
        com.fasterxml.jackson.databind.JsonNode report = com.condense.core.Mappers.JSON.readTree(text);
        assertThat(report.has("ok")).isTrue();
        assertThat(report.has("schema_version")).isTrue();
    }

    private static Path workspaceFile(String name) throws Exception {
        Path dir = Path.of(System.getProperty("user.dir", "."))
            .resolve("target")
            .resolve("mcp-handlers-" + UUID.randomUUID());
        Files.createDirectories(dir);
        return dir.resolve(name);
    }

    private JsonNode callTool(String name, String argumentsJson) throws Exception {
        String line = """
            {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"%s","arguments":%s}}
            """.formatted(name, argumentsJson.trim()).trim();
        String response = server.handleLine(line);
        JsonNode root = McpMessages.RPC.readTree(response);
        assertThat(root.has("result")).as(response).isTrue();
        return root.get("result");
    }

    private static HookInstaller noHooks() {
        return new NoHooks();
    }

    @Vetoed
    static final class NoHooks extends HookInstaller {
        @Override
        public List<HookInstaller.StatusResult> showAll() {
            return List.of(new HookInstaller.StatusResult(HookTool.CURSOR, false, Path.of("/tmp/none")));
        }
    }

    @Vetoed
    static final class FixedRegistry extends StrategyRegistry {
        @Override
        public FilterStrategy lookup(String[] args) {
            if (args != null && args.length >= 1 && "pytest".equals(args[0])) {
                return new PytestFilter();
            }
            if (args != null && args.length >= 2 && "git".equals(args[0]) && "status".equals(args[1])) {
                return new GitStatusFilter();
            }
            return new com.condense.core.PassthroughStrategy();
        }
    }

    @Vetoed
    static final class FeedingExecutor extends CommandExecutor {
        @Override
        public ExecutionResult execute(List<String> args, Duration timeout, StreamListener listener) {
            String stdout;
            int exit;
            if (args != null && !args.isEmpty() && "pytest".equals(args.get(0))) {
                try {
                    stdout = CorpusRunner.loadFixture("fixtures/pytest/typical.txt");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                exit = 1;
            } else {
                try {
                    stdout = CorpusRunner.loadFixture("fixtures/git-status/mixed.txt");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                exit = 0;
            }
            byte[] bytes = stdout.getBytes(StandardCharsets.UTF_8);
            if (listener != null) {
                listener.onStdout(bytes, bytes.length);
            }
            return new ExecutionResult(exit, stdout, "", 5L);
        }
    }
}
