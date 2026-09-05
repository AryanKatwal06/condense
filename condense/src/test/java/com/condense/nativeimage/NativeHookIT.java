package com.condense.nativeimage;

import com.condense.persist.LegacyDatabase;
import com.condense.persist.SchemaMigrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native-image proof for hook integrity, backups, schema v2, and MCP fallback.
 * Never skips.
 */
class NativeHookIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateDirs() throws Exception {
        Files.createDirectories(configDir());
        Files.createDirectories(dataDir());
        Files.createDirectories(fakeHome());
    }

    @Test
    void helpMentionsIntegrityShowAndNewTools() throws Exception {
        NativeBinarySupport.CliResult result = run("init", "--help");
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isZero();
        assertThat(result.stdout()).containsIgnoringCase("integrity");
        assertThat(result.stdout()).contains("--show");
        assertThat(result.stdout()).contains("codex");
        assertThat(result.stdout()).contains("opencode");
        assertThat(result.stdout()).contains("antigravity");
    }

    @Test
    void cursorInstallWritesScriptBackupAndDoctorOk() throws Exception {
        Path hooksJson = fakeHome().resolve(".cursor").resolve("hooks.json");
        Files.createDirectories(hooksJson.getParent());
        Files.writeString(hooksJson, "{ \"version\": 1 }\n");

        NativeBinarySupport.CliResult init = run("init", "--tool", "cursor");
        assertThat(init.exitCode())
            .as("stdout=%s stderr=%s", init.stdout(), init.stderr())
            .isZero();
        Path script = fakeHome().resolve(".cursor").resolve("hooks").resolve("condense-hook.sh");
        assertThat(script).exists();
        assertThat(Files.readString(script)).contains("mypy");
        assertThat(Files.readString(script)).doesNotContain("{{CONDENSE_COMMANDS}}");
        try (var stream = Files.list(dataDir().resolve("backups"))) {
            assertThat(stream.anyMatch(p -> p.getFileName().toString().startsWith("cursor-"))).isTrue();
        }

        NativeBinarySupport.CliResult doctor = run("doctor", "--format", "json");
        assertThat(doctor.exitCode())
            .as("stdout=%s stderr=%s", doctor.stdout(), doctor.stderr())
            .isZero();
        JsonNode diagnosis = JSON.readTree(doctor.stdout().strip());
        assertThat(diagnosis.isObject()).isTrue();
        assertThat(diagnosis.get("schema_version").asInt()).isEqualTo(SchemaMigrator.TARGET_VERSION);
        boolean sawOk = false;
        for (JsonNode hook : diagnosis.get("hooks")) {
            if (hook.get("tool").asText().toLowerCase().contains("cursor") && hook.get("installed").asBoolean()) {
                assertThat(hook.get("integrity").asText()).isEqualTo("ok");
                sawOk = true;
            }
        }
        assertThat(sawOk).as(doctor.stdout()).isTrue();
        assertThat(diagnosis.get("hook_events").isArray()).isTrue();
        assertThat(diagnosis.get("hook_events").size()).isGreaterThan(0);
    }

    @Test
    void hermesInstallWritesPluginAndInit() throws Exception {
        NativeBinarySupport.CliResult init = run("init", "--tool", "hermes");
        assertThat(init.exitCode())
            .as("stdout=%s stderr=%s", init.stdout(), init.stderr())
            .isZero();
        Path plugin = fakeHome().resolve(".hermes").resolve("plugins").resolve("condense").resolve("plugin.yaml");
        Path python = fakeHome().resolve(".hermes").resolve("plugins").resolve("condense").resolve("__init__.py");
        assertThat(plugin).exists();
        assertThat(python).exists();
        assertThat(Files.readString(python)).contains("mypy");
        assertThat(Files.readString(python)).doesNotContain("{{CONDENSE_COMMANDS}}");
    }

    @Test
    void tamperedScriptIsReported() throws Exception {
        run("init", "--tool", "cursor");
        Path script = fakeHome().resolve(".cursor").resolve("hooks").resolve("condense-hook.sh");
        Files.writeString(script, Files.readString(script) + "\n# tampered\n");

        NativeBinarySupport.CliResult doctor = run("doctor", "--format", "json");
        assertThat(doctor.exitCode()).isZero();
        JsonNode diagnosis = JSON.readTree(doctor.stdout().strip());
        boolean sawTampered = false;
        for (JsonNode hook : diagnosis.get("hooks")) {
            if (hook.get("tool").asText().toLowerCase().contains("cursor")) {
                assertThat(hook.get("integrity").asText()).isEqualTo("tampered");
                sawTampered = true;
            }
        }
        assertThat(sawTampered).as(doctor.stdout()).isTrue();
    }

    @Test
    void seededV1DatabaseMigratesToV2WithHookEvents() throws Exception {
        Path db = dataDir().resolve("condense.db");
        LegacyDatabase.writeV1(db);
        NativeBinarySupport.CliResult gain = run("gain", "--format", "json");
        assertThat(gain.exitCode()).isZero();

        Driver driver = new org.sqlite.JDBC();
        try (Connection connection = driver.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement st = connection.createStatement()) {
            try (ResultSet version = st.executeQuery("PRAGMA user_version")) {
                assertThat(version.next()).isTrue();
                assertThat(version.getInt(1)).isEqualTo(2);
            }
            try (ResultSet tables = st.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='hook_events'")) {
                assertThat(tables.next()).isTrue();
            }
        }
    }

    @Test
    void mcpStillServesToolsList() throws Exception {
        NativeBinarySupport.StartedRun session = NativeBinarySupport.start(
            configDir(), dataDir(), null, tempDir, extraEnv(), "mcp", "--start");
        session.writeStdin("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"native-hook-it","version":"0"}}}
            {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
            """);
        session.closeStdin();
        NativeBinarySupport.CliResult result = session.await();
        assertThat(result.stdout()).contains("\"name\"");
        assertThat(result.stdout()).contains("run");
    }

    private NativeBinarySupport.CliResult run(String... args) throws Exception {
        return NativeBinarySupport.run(configDir(), dataDir(), null, tempDir, extraEnv(), args);
    }

    private Map<String, String> extraEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("CONDENSE_TEST_HOME", fakeHome().toAbsolutePath().toString());
        env.put("HOME", fakeHome().toAbsolutePath().toString());
        env.put("USERPROFILE", fakeHome().toAbsolutePath().toString());
        return env;
    }

    private Path configDir() {
        return tempDir.resolve("config");
    }

    private Path dataDir() {
        return tempDir.resolve("data");
    }

    private Path fakeHome() {
        return tempDir.resolve("home");
    }
}
