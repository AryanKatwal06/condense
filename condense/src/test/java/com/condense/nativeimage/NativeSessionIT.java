package com.condense.nativeimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native-image proof that session intelligence and failure reporting commands execute
 * properly inside the compiled native executable.
 */
class NativeSessionIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path configDir;
    private Path dataDir;

    @BeforeEach
    void isolateDirs() throws Exception {
        configDir = tempDir.resolve("config");
        dataDir = tempDir.resolve("data");
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
    }

    @Test
    void sessionHelpMentionsAnalyze() throws Exception {
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir, dataDir, "session", "--help"
        );
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isZero();
        assertThat(result.stdout()).containsIgnoringCase("session");
        assertThat(result.stdout()).contains("analyze");
    }

    @Test
    void sessionAnalyzeEmptyDirectoryReturnsJson() throws Exception {
        Path emptySessionDir = tempDir.resolve("sessions");
        Files.createDirectories(emptySessionDir);

        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir, dataDir, "session", "analyze", "--path", emptySessionDir.toString(), "--format", "json"
        );
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isZero();

        JsonNode json = JSON.readTree(result.stdout());
        assertThat(json.has("total_sessions")).isTrue();
        assertThat(json.get("total_sessions").asInt()).isZero();
    }

    @Test
    void reportHelpMentionsConsentAndPreview() throws Exception {
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir, dataDir, "report", "--help"
        );
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isZero();
        assertThat(result.stdout()).contains("--preview");
        assertThat(result.stdout()).contains("--opt-in");
        assertThat(result.stdout()).contains("--opt-out");
        assertThat(result.stdout()).contains("--export");
    }

    @Test
    void reportPreviewOutputsSanitizedSchema() throws Exception {
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir, dataDir, "report", "--preview"
        );
        assertThat(result.exitCode())
            .as("stdout=%s stderr=%s", result.stdout(), result.stderr())
            .isZero();

        JsonNode json = JSON.readTree(result.stdout());
        assertThat(json.has("schema_version")).isTrue();
        assertThat(json.get("schema_version").asInt()).isEqualTo(1);
        assertThat(json.has("duration_bucket")).isTrue();
        assertThat(json.has("output_length_bucket")).isTrue();
    }
}
