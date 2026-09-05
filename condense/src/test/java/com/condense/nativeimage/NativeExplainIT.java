package com.condense.nativeimage;

import com.condense.core.Utf8WeightedTokenEstimator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Native-image proof that {@code condense explain} accounts stages inside
 * the shipped binary and does not write analytics rows.
 */
class NativeExplainIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateDirs() throws Exception {
        Files.createDirectories(configDir());
        Files.createDirectories(dataDir());
    }

    @Test
    void explainHelpMentionsTheCommand() throws Exception {
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(), dataDir(), "explain", "--help");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout() + result.stderr()).containsIgnoringCase("explain");
    }

    @Test
    void explainJsonFromFixtureAccountsStagesAndDoesNotPersist() throws Exception {
        Path fixture = tempDir.resolve("pytest.txt");
        try (var in = NativeExplainIT.class.getResourceAsStream("/fixtures/pytest/typical.txt")) {
            assertThat(in).as("pytest fixture").isNotNull();
            Files.write(fixture, in.readAllBytes());
        }

        NativeBinarySupport.CliResult explained = NativeBinarySupport.run(
            configDir(),
            dataDir(),
            "explain",
            "--input",
            fixture.toAbsolutePath().toString(),
            "--exit-code",
            "1",
            "--format",
            "json",
            "pytest"
        );
        assertThat(explained.exitCode())
            .as("stdout=%s stderr=%s", explained.stdout(), explained.stderr())
            .isZero();
        JsonNode report = JSON.readTree(explained.stdout());
        assertThat(report.get("tier").asText()).isEqualTo("builtin");
        assertThat(report.get("source").asText()).contains("pytest");
        assertThat(report.get("stages").isArray()).isTrue();
        assertThat(report.get("stages").size()).isGreaterThan(0);
        assertThat(report.get("estimator").get("name").asText())
            .isEqualTo(Utf8WeightedTokenEstimator.NAME);
        assertThat(report.get("filtered_output").asText()).startsWith("condense[filtered]");

        int net = 0;
        for (JsonNode stage : report.get("stages")) {
            if ("skipped".equals(stage.get("status").asText())) {
                continue;
            }
            net += stage.get("dropped_lines").asInt() - stage.get("added_lines").asInt();
        }
        assertThat(net).isEqualTo(
            report.get("input_lines").asInt() - report.get("output_lines").asInt());

        NativeBinarySupport.CliResult gain = NativeBinarySupport.run(
            configDir(), dataDir(), "gain", "--format", "json");
        assertThat(gain.exitCode()).isZero();
        JsonNode analytics = JSON.readTree(gain.stdout());
        assertThat(analytics.get("total_commands").asLong())
            .as("explain must not insert tracking rows: %s", gain.stdout())
            .isZero();
    }

    @Test
    void missingInputExitsOne() throws Exception {
        NativeBinarySupport.CliResult result = NativeBinarySupport.run(
            configDir(),
            dataDir(),
            "explain",
            "--input",
            tempDir.resolve("missing.txt").toAbsolutePath().toString(),
            "--format",
            "json",
            "pytest"
        );
        assertThat(result.exitCode()).isEqualTo(1);
    }

    @Test
    void gainTopTenRendersTheTable() throws Exception {
        NativeBinarySupport.CliResult proxied = NativeBinarySupport.run(
            configDir(), dataDir(), NativeBinarySupport.trivialSucceedingCommand());
        assertThat(proxied.exitCode()).isZero();

        NativeBinarySupport.CliResult top = NativeBinarySupport.run(
            configDir(), dataDir(), "gain", "--top", "10");
        assertThat(top.exitCode()).isZero();
        assertThat(top.stdout()).contains("Top Commands by Tokens Saved");
    }

    private Path configDir() {
        return tempDir.resolve("config");
    }

    private Path dataDir() {
        return tempDir.resolve("data");
    }
}
